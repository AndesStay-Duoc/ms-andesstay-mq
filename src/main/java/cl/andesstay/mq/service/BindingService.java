package cl.andesstay.mq.service;

import cl.andesstay.mq.broker.ManagementApiClient;
import cl.andesstay.mq.config.RabbitMQProperties;
import cl.andesstay.mq.dto.BindingResumen;
import cl.andesstay.mq.dto.CrearBindingRequest;
import cl.andesstay.mq.dto.OperacionResultado;
import cl.andesstay.mq.dto.TipoDestino;
import cl.andesstay.mq.web.RecursoNoEncontradoException;
import cl.andesstay.mq.web.RecursoProtegidoException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Altas, bajas y consultas de bindings.
 *
 * Un binding es la regla que conecta un exchange con un destino, y es lo que de verdad decide por
 * dónde circula un mensaje: un exchange sin bindings acepta publicaciones y las descarta. En la
 * topología de AndesStay son los nueve que conectan cmd.direct y cmd.topic con las tres colas de
 * trabajo, y cmd.dead.dlx con las tres DLQ.
 *
 * A diferencia de las colas y los exchanges, un binding no tiene nombre propio: lo identifica la
 * tripleta (exchange, destino, routing key). Por eso la baja lleva los tres valores como parámetros
 * de consulta en lugar de un identificador en la ruta.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BindingService {

    private static final String RECURSO = "binding";

    private final AmqpAdmin amqpAdmin;
    private final ManagementApiClient gestion;
    private final RabbitMQProperties propiedades;

    /**
     * Declara el binding. Repetirlo con la misma tripleta es idempotente y no duplica la regla.
     *
     * Se rechazan los bindings protegidos (ver esProtegido). Uno desde un exchange del sistema
     * hacia una cola que no es del sistema sí se permite: solo agrega una copia de los mensajes y
     * no le quita nada a la topología, y se puede volver a quitar por la misma API.
     */
    public OperacionResultado crear(CrearBindingRequest peticion) {
        rechazarSiEsProtegido(peticion.exchange(), peticion.destino(),
                peticion.tipoDestinoOPorOmision(), "crear");

        Binding binding = construir(
                peticion.exchange(),
                peticion.destino(),
                peticion.tipoDestinoOPorOmision().aTipoAmqp(),
                peticion.routingKeyOVacia(),
                peticion.argumentos());

        amqpAdmin.declareBinding(binding);

        log.info("[Bindings] Declarado {} -> {} ({}) rk={}",
                peticion.exchange(), peticion.destino(),
                peticion.tipoDestinoOPorOmision(), peticion.routingKeyOVacia());

        return OperacionResultado.de(RECURSO, descripcion(peticion.exchange(), peticion.destino(),
                peticion.routingKeyOVacia()), "declarado", "declarado");
    }

    /**
     * Lista los bindings del vhost.
     *
     * Aparecen también los bindings implícitos del exchange por omisión, con el origen vacío. Se
     * dejan a la vista a propósito: son los que explican por qué una cola recibe los mensajes
     * publicados con su propio nombre como routing key, que es el mecanismo con el que
     * ms-andesstay-notify encola los reintentos.
     */
    public List<BindingResumen> listar() {
        return gestion.bindings().stream()
                .map(this::aResumen)
                .sorted(Comparator
                        .comparing(BindingResumen::exchange,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(BindingResumen::destino,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * Elimina el binding.
     *
     * Los bindings protegidos (ver esProtegido) no se eliminan: quitar cmd.direct -> q.cmd.email
     * con email.send cortaría el flujo de correos sin borrar nada visible y sin que ningún
     * servicio diera error. Fuera de eso no hay restricción, y la respuesta advierte que lo que se
     * publique por esa ruta se descartará.
     *
     * Antes de quitarlo se comprueba que exista: queue.unbind y exchange.unbind responden bien aunque
     * la regla no exista, así que sin la comprobación una baja equivocada respondería 204.
     */
    public OperacionResultado eliminar(String exchange, String destino,
                                       TipoDestino tipoDestino, String routingKey) {
        if (exchange == null || exchange.isEmpty()) {
            // El exchange por omisión mantiene un binding implícito a cada cola por su nombre. No
            // es una regla declarada, así que no hay nada que desenlazar: el broker rechazaría la
            // operación con un error menos claro que este.
            throw new IllegalArgumentException(
                    "el exchange por omisión tiene bindings implícitos a cada cola por su nombre, "
                            + "y esos no se pueden eliminar");
        }

        String rk = routingKey == null ? "" : routingKey;
        rechazarSiEsProtegido(exchange, destino, tipoDestino, "eliminar");

        ManagementApiClient.BindingApi existente = gestion
                .bindingsEntre(exchange, destino, tipoDestino == TipoDestino.QUEUE).stream()
                .filter(b -> rk.equals(b.routingKey() == null ? "" : b.routingKey()))
                .findFirst()
                .orElseThrow(() -> new RecursoNoEncontradoException(RECURSO, descripcion(exchange, destino, rk)));

        // Los argumentos tienen que ser los del binding existente: RabbitMQ identifica la regla
        // también por ellos, y un unbind con otros argumentos no quita nada y responde bien.
        amqpAdmin.removeBinding(construir(exchange, destino, tipoDestino.aTipoAmqp(), rk,
                existente.arguments()));

        log.warn("[Bindings] Eliminado {} -> {} ({}) rk={}", exchange, destino, tipoDestino, rk);
        return OperacionResultado.de(RECURSO, descripcion(exchange, destino, rk), "eliminado",
                "los mensajes que se publiquen por esta ruta se descartarán sin aviso");
    }

    /**
     * Un binding está protegido en dos casos:
     *
     *   - forma parte de la topología del sistema, la que declara ms-andesstay-notify: sale de un
     *     exchange protegido y llega a un recurso protegido, como cmd.direct -> q.cmd.email o
     *     cmd.dead.dlx -> q.cmd.email.dlq;
     *   - llega a una cola protegida, venga de donde venga: crearlo metería mensajes ajenos en una
     *     cola del sistema, y quitarlo podría cortar un flujo que alguien sí espera.
     *
     * Que el ORIGEN sea un exchange protegido no basta: cmd.topic -> q.demo solo agrega una copia de
     * los mensajes, y se puede crear y eliminar libremente.
     */
    private void rechazarSiEsProtegido(String exchange, String destino, TipoDestino tipoDestino,
                                       String operacion) {
        boolean delSistema = propiedades.esProtegido(exchange) && propiedades.esProtegido(destino);
        if (delSistema) {
            throw new RecursoProtegidoException(destino,
                    operacion + " el binding del sistema " + descripcionCorta(exchange, destino));
        }
        if (tipoDestino == TipoDestino.QUEUE && propiedades.esProtegido(destino)) {
            throw new RecursoProtegidoException(destino,
                    operacion + " un binding hacia la cola '" + destino + "'");
        }
    }

    private static String descripcionCorta(String exchange, String destino) {
        return "'" + exchange + " -> " + destino + "'";
    }

    private Binding construir(String exchange, String destino,
                              Binding.DestinationType tipoDestino, String routingKey,
                              Map<String, Object> argumentos) {
        // El orden de los argumentos de Binding es (destino, tipo, exchange, rk): el destino va
        // primero y el origen tercero, al contrario de cómo se lee la regla. Equivocarlo compila
        // sin problema y produce un binding al revés.
        return new Binding(destino, tipoDestino, exchange, routingKey, argumentos);
    }

    private String descripcion(String exchange, String destino, String routingKey) {
        return exchange + " -> " + destino + " (rk=" + routingKey + ")";
    }

    private BindingResumen aResumen(ManagementApiClient.BindingApi api) {
        return new BindingResumen(
                api.source(),
                api.destination(),
                api.destinationType(),
                api.routingKey(),
                api.arguments() == null ? Map.of() : api.arguments());
    }
}
