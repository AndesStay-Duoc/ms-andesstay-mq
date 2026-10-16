package cl.andesstay.mq.broker;

import cl.andesstay.mq.config.RabbitMQProperties;
import cl.andesstay.mq.web.ApiDeGestionException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lee el inventario del broker por su API HTTP de gestión.
 *
 * POR QUÉ EXISTE ESTA CLASE Y NO BASTA AmqpAdmin. El protocolo AMQP 0-9-1 define `queue.declare`,
 * `queue.delete`, `exchange.declare`, `queue.bind` y compañía, así que todas las ESCRITURAS se
 * resuelven con AmqpAdmin. Lo que AMQP no define es ninguna operación de listado: no existe un
 * `queue.list`. Enumerar las colas, los exchanges o los bindings de un vhost solo se puede hacer
 * contra la API de gestión del puerto 15672.
 *
 * De ahí la asimetría del servicio, que conviene tener presente al desplegar: las escrituras van
 * por 5672 y las lecturas de inventario por 15672. El compose publica el 15672 solo en loopback,
 * así que este cliente funciona porque el contenedor comparte red con el broker, no porque el
 * puerto esté abierto.
 */
@Slf4j
@Component
public class ManagementApiClient {

    /**
     * El parámetro `columns` recorta la respuesta en el servidor.
     *
     * No es cosmético: la API de gestión devuelve más de cuarenta campos por cola, con tasas y
     * deltas calculados, y en un broker con histórico la respuesta completa se mide en cientos de
     * kilobytes. Se piden solo los campos que la respuesta de esta API usa.
     */
    private static final String COLUMNAS_COLAS =
            "name,messages,consumers,durable,exclusive,auto_delete,arguments,state,node,policy";
    private static final String COLUMNAS_EXCHANGES =
            "name,type,durable,auto_delete,internal,arguments";
    private static final String COLUMNAS_BINDINGS =
            "source,destination,destination_type,routing_key,arguments";

    private final RestClient cliente;
    private final String vhost;

    public ManagementApiClient(RestClient managementRestClient, RabbitMQProperties propiedades) {
        this.cliente = managementRestClient;
        this.vhost = propiedades.vhost();
    }

    public List<ColaApi> colas() {
        return listar("/api/queues/{vhost}", COLUMNAS_COLAS, new ParameterizedTypeReference<List<ColaApi>>() {
        });
    }

    public List<ExchangeApi> exchanges() {
        return listar("/api/exchanges/{vhost}", COLUMNAS_EXCHANGES, new ParameterizedTypeReference<List<ExchangeApi>>() {
        });
    }

    public List<BindingApi> bindings() {
        return listar("/api/bindings/{vhost}", COLUMNAS_BINDINGS, new ParameterizedTypeReference<List<BindingApi>>() {
        });
    }

    /**
     * Los bindings de un exchange hacia un destino concreto, con cualquier routing key.
     *
     * Sirve para saber si un binding existe antes de quitarlo: queue.unbind y exchange.unbind
     * responden bien aunque la regla no exista, así que la baja por sí sola no lo distingue. Si
     * el exchange o el destino no existen, la API de gestión responde 404 y aquí llega como lista
     * vacía, que para quien pregunta significa lo mismo: no hay tal binding.
     *
     * @param destinoEsCola true para una cola (segmento "q" de la ruta), false para un exchange ("e")
     */
    public List<BindingApi> bindingsEntre(String exchange, String destino, boolean destinoEsCola) {
        String ruta = "/api/bindings/{vhost}/e/{exchange}/" + (destinoEsCola ? "q" : "e") + "/{destino}";
        try {
            List<BindingApi> resultado = cliente.get()
                    .uri(builder -> builder.path(ruta)
                            .queryParam("columns", COLUMNAS_BINDINGS).build(vhost, exchange, destino))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<BindingApi>>() {
                    });
            return resultado == null ? List.of() : resultado;

        } catch (HttpClientErrorException.NotFound e) {
            return List.of();

        } catch (ResourceAccessException e) {
            log.error("[Gestión] No se alcanzó la API de gestión en {}: {}", ruta, e.getMessage());
            throw new ApiDeGestionException(
                    "no se pudo contactar la API de gestión del broker", false, e);

        } catch (RestClientResponseException e) {
            log.error("[Gestión] La API de gestión respondió {} en {}: {}",
                    e.getStatusCode(), ruta, e.getResponseBodyAsString());
            throw new ApiDeGestionException(
                    "la API de gestión respondió " + e.getStatusCode(), true, e);
        }
    }

    /**
     * Un solo exchange, o vacío si no existe.
     *
     * Un 404 aquí es una respuesta normal —el exchange no existe— y no un fallo de la API, así que
     * se devuelve vacío en lugar de lanzar. La API de gestión responde lo mismo cuando el que no
     * existe es el vhost; ese caso ya lo delata el listado, que sí lo trata como error.
     *
     * El nombre viaja como variable de plantilla por la misma razón que el vhost: así se codifica.
     */
    public Optional<ExchangeApi> exchange(String nombre) {
        String ruta = "/api/exchanges/{vhost}/{nombre}";
        try {
            return Optional.ofNullable(cliente.get()
                    .uri(builder -> builder.path(ruta)
                            .queryParam("columns", COLUMNAS_EXCHANGES).build(vhost, nombre))
                    .retrieve()
                    .body(ExchangeApi.class));

        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();

        } catch (ResourceAccessException e) {
            log.error("[Gestión] No se alcanzó la API de gestión en {}: {}", ruta, e.getMessage());
            throw new ApiDeGestionException(
                    "no se pudo contactar la API de gestión del broker", false, e);

        } catch (RestClientResponseException e) {
            log.error("[Gestión] La API de gestión respondió {} en {}: {}",
                    e.getStatusCode(), ruta, e.getResponseBodyAsString());
            throw new ApiDeGestionException(
                    "la API de gestión respondió " + e.getStatusCode(), true, e);
        }
    }

    /**
     * El vhost viaja como variable de plantilla y no concatenado: así se codifica.
     *
     * Importa porque el vhost por omisión de RabbitMQ se llama "/" y en la URL tiene que ir como
     * %2F. Concatenarlo produciría /api/queues// y un 404 desconcertante. Aquí el vhost es
     * "andesstay" y no se nota, pero el defecto estaría puesto.
     */
    private <T> List<T> listar(String ruta, String columnas, ParameterizedTypeReference<List<T>> tipo) {
        try {
            List<T> resultado = cliente.get()
                    .uri(builder -> builder.path(ruta).queryParam("columns", columnas).build(vhost))
                    .retrieve()
                    .body(tipo);
            return resultado == null ? List.of() : resultado;

        } catch (ResourceAccessException e) {
            // No hubo conexión: el puerto está cerrado, el host no resuelve o venció el timeout.
            log.error("[Gestión] No se alcanzó la API de gestión en {}: {}", ruta, e.getMessage());
            throw new ApiDeGestionException(
                    "no se pudo contactar la API de gestión del broker", false, e);

        } catch (RestClientResponseException e) {
            // Respondió, pero con un error. Lo más probable es un 401 por credenciales sin la
            // etiqueta "administrator", o un 404 si el vhost configurado no existe.
            log.error("[Gestión] La API de gestión respondió {} en {}: {}",
                    e.getStatusCode(), ruta, e.getResponseBodyAsString());
            throw new ApiDeGestionException(
                    "la API de gestión respondió " + e.getStatusCode(), true, e);
        }
    }

    // ── Proyecciones de la respuesta del broker ──────────────────────────────
    // Los nombres de campo son los de la API de gestión, en snake_case y en inglés: son un
    // contrato externo y renombrarlos aquí solo añadiría una traducción más. La conversión a los
    // DTO públicos la hacen los servicios.

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ColaApi(
            String name,
            Integer messages,
            Integer consumers,
            Boolean durable,
            Boolean exclusive,
            @JsonProperty("auto_delete") Boolean autoDelete,
            Map<String, Object> arguments,
            String state,
            String node,
            String policy) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExchangeApi(
            String name,
            String type,
            Boolean durable,
            @JsonProperty("auto_delete") Boolean autoDelete,
            Boolean internal,
            Map<String, Object> arguments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BindingApi(
            String source,
            String destination,
            @JsonProperty("destination_type") String destinationType,
            @JsonProperty("routing_key") String routingKey,
            Map<String, Object> arguments) {
    }
}
