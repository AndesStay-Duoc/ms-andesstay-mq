package cl.andesstay.mq.service;

import cl.andesstay.mq.broker.ManagementApiClient;
import cl.andesstay.mq.config.RabbitMQProperties;
import cl.andesstay.mq.dto.CrearExchangeRequest;
import cl.andesstay.mq.dto.ExchangeResumen;
import cl.andesstay.mq.dto.OperacionResultado;
import cl.andesstay.mq.web.RecursoNoEncontradoException;
import cl.andesstay.mq.web.RecursoProtegidoException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Exchange;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Altas, bajas y consultas de exchanges.
 *
 * Las cuatro familias de AMQP se exponen por igual: direct, topic, fanout y headers. El caso
 * AndesStay usa las dos primeras —cmd.direct para el enrutado exacto y cmd.topic para los
 * patrones— y la tercera para el dead letter exchange, que también es direct.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeService {

    private static final String RECURSO = "exchange";

    private final AmqpAdmin amqpAdmin;
    private final ManagementApiClient gestion;
    private final RabbitMQProperties propiedades;

    /**
     * Declara el exchange.
     *
     * Como con las colas, declarar dos veces con el mismo tipo y la misma durabilidad es
     * idempotente; cambiar el tipo de un exchange que ya existe es lo que el broker rechaza con
     * PRECONDITION_FAILED. Por eso declarar un exchange ya conocido es seguro y declarar
     * cmd.direct como topic no lo es.
     */
    public OperacionResultado crear(CrearExchangeRequest peticion) {
        NombresAmqp.validar(RECURSO, peticion.nombre());

        Exchange exchange = peticion.tipo().construir(
                peticion.nombre(),
                peticion.durableOPorOmision(),
                peticion.autoDeleteOPorOmision(),
                peticion.argumentos());

        // declareExchange no devuelve nada, ni siquiera un booleano: con un fallo real sube una
        // AmqpException, y el único caso de omisión silenciosa —el prefijo reservado— ya lo
        // descartó NombresAmqp.
        amqpAdmin.declareExchange(exchange);

        log.info("[Exchanges] Declarado {} tipo={} durable={} autoDelete={} argumentos={}",
                peticion.nombre(), peticion.tipo(), peticion.durableOPorOmision(),
                peticion.autoDeleteOPorOmision(), peticion.argumentos());

        return OperacionResultado.de(RECURSO, peticion.nombre(), "declarado",
                "declarado de tipo " + peticion.tipo().nombreAmqp());
    }

    /** Lista los exchanges del vhost. Pasa por la API de gestión: AMQP no sabe listar. */
    public List<ExchangeResumen> listar() {
        return gestion.exchanges().stream()
                .map(this::aResumen)
                .sorted(Comparator.comparing(ExchangeResumen::nombre,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * Describe un solo exchange.
     *
     * Va por la API de gestión, igual que el listado: AMQP no tiene una operación para leer un
     * exchange. La declaración pasiva solo dice si existe, no su tipo ni sus argumentos, que es
     * justamente lo que se viene a consultar.
     */
    public ExchangeResumen describir(String nombre) {
        return gestion.exchange(nombre)
                .map(this::aResumen)
                .orElseThrow(() -> new RecursoNoEncontradoException(RECURSO, nombre));
    }

    /**
     * Elimina el exchange.
     *
     * Borrar un exchange no borra las colas que tuviera enlazadas, pero sí sus bindings: los
     * mensajes que se publicaran contra él dejan de llegar a ninguna parte. De ahí la protección de
     * los nombres de la topología, que forzar no levanta; el parámetro se conserva para no romper
     * a quien ya lo envía.
     */
    public OperacionResultado eliminar(String nombre, boolean forzar) {
        if (propiedades.esProtegido(nombre)) {
            throw new RecursoProtegidoException("el exchange", nombre, "eliminar");
        }

        // Se comprueba ANTES de borrar porque desde RabbitMQ 3.13 exchange.delete es idempotente:
        // sobre un exchange inexistente deleteExchange devuelve true. AMQP no tiene forma de
        // consultar un exchange sin declararlo, así que la comprobación va por la API de gestión;
        // si el 15672 no responde, la baja sale como 503 en lugar de responder sin saber.
        if (gestion.exchange(nombre).isEmpty()) {
            throw new RecursoNoEncontradoException(RECURSO, nombre);
        }

        // Para los brokers anteriores, donde deleteExchange devuelve false si no existe.
        if (!amqpAdmin.deleteExchange(nombre)) {
            throw new RecursoNoEncontradoException(RECURSO, nombre);
        }

        log.warn("[Exchanges] Eliminado {}{}", nombre, forzar ? " (forzado)" : "");
        return OperacionResultado.de(RECURSO, nombre, "eliminado",
                "se eliminaron también sus bindings; las colas enlazadas siguen existiendo");
    }

    private ExchangeResumen aResumen(ManagementApiClient.ExchangeApi api) {
        return new ExchangeResumen(
                api.name(),
                api.type(),
                api.durable(),
                api.autoDelete(),
                api.internal(),
                api.arguments() == null ? Map.of() : api.arguments(),
                propiedades.esProtegido(api.name()));
    }
}
