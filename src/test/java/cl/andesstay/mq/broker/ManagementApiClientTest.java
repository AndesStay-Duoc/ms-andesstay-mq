package cl.andesstay.mq.broker;

import cl.andesstay.mq.config.RabbitMQProperties;
import cl.andesstay.mq.web.ApiDeGestionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * La consulta de un solo exchange contra la API de gestión, sin broker.
 *
 * Lo que importa es la frontera entre «no existe» y «falló»: un 404 es una respuesta normal y tiene
 * que llegar como vacío, mientras que un 401 es configuración rota del servicio y tiene que seguir
 * saliendo como error.
 */
class ManagementApiClientTest {

    private static final String BASE = "http://gestion:15672";
    private static final String COLUMNAS = "?columns=name,type,durable,auto_delete,internal,arguments";

    private MockRestServiceServer servidor;
    private ManagementApiClient cliente;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        servidor = MockRestServiceServer.bindTo(builder).build();
        RabbitMQProperties propiedades = new RabbitMQProperties(
                "andesstay",
                new RabbitMQProperties.Management(BASE, "admin", "secreto"),
                List.of());
        cliente = new ManagementApiClient(builder.build(), propiedades);
    }

    @Test
    @DisplayName("un exchange existente se lee de /api/exchanges/{vhost}/{nombre}")
    void existente() {
        servidor.expect(requestTo(BASE + "/api/exchanges/andesstay/cmd.direct" + COLUMNAS))
                .andRespond(withSuccess("""
                        {"name":"cmd.direct","type":"direct","durable":true,"auto_delete":false,
                         "internal":false,"arguments":{}}""", MediaType.APPLICATION_JSON));

        Optional<ManagementApiClient.ExchangeApi> exchange = cliente.exchange("cmd.direct");

        assertTrue(exchange.isPresent());
        assertEquals("direct", exchange.get().type());
        assertEquals(false, exchange.get().autoDelete());
        servidor.verify();
    }

    @Test
    @DisplayName("un 404 es «no existe»: llega vacío y no como error de la API")
    void inexistenteEsVacio() {
        servidor.expect(requestTo(BASE + "/api/exchanges/andesstay/ex.no.existe" + COLUMNAS))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .body("{\"error\":\"Object Not Found\",\"reason\":\"Not Found\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertTrue(cliente.exchange("ex.no.existe").isEmpty());
        servidor.verify();
    }

    @Test
    @DisplayName("un 401 sigue siendo un fallo: credenciales sin la etiqueta administrator")
    void credencialesRechazadas() {
        servidor.expect(requestTo(BASE + "/api/exchanges/andesstay/cmd.direct" + COLUMNAS))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        ApiDeGestionException e = assertThrows(ApiDeGestionException.class,
                () -> cliente.exchange("cmd.direct"));

        assertTrue(e.esAlcanzable(), "respondió, así que la API sí es alcanzable");
    }
}
