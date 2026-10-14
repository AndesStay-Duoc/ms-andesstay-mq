package cl.andesstay.mq.service;

import cl.andesstay.mq.broker.ManagementApiClient;
import cl.andesstay.mq.config.RabbitMQProperties;
import cl.andesstay.mq.dto.ColaResumen;
import cl.andesstay.mq.dto.CrearColaRequest;
import cl.andesstay.mq.dto.OperacionResultado;
import cl.andesstay.mq.web.RecursoNoEncontradoException;
import cl.andesstay.mq.web.RecursoProtegidoException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Altas, bajas, purgas y consultas de colas.
 *
 * Aquí vive la lógica de negocio del recurso: qué se puede crear, qué hay que confirmar antes de
 * destruir y cómo se traduce lo que informa el broker. La configuración de la mensajería —la
 * conexión, el AmqpAdmin y el cliente de la API de gestión— está en los paquetes config y broker,
 * y esta clase solo los recibe ya construidos.
 *
 * EFECTO QUE CONVIENE CONOCER. El broker tiene aplicada la política ha-colas-cmd con el patrón
 * ^q\.cmd\. (ver infra/mq/compose.yml), así que toda cola creada por esta API cuyo nombre empiece
 * por q.cmd. queda espejada automáticamente en los dos nodos del clúster. Es el comportamiento
 * deseable, pero no es obvio al leer la petición: el espejado no aparece en ningún argumento, lo
 * decide el nombre. Se comprueba en el campo politica de la respuesta del listado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private static final String RECURSO = "cola";

    /**
     * Se inyecta la INTERFAZ, no RabbitAdmin.
     *
     * Spring Boot registra el bean con el tipo declarado AmqpAdmin, y pedir un RabbitAdmin falla en
     * el arranque por no encontrar un bean de ese tipo, aunque la instancia concreta sea
     * exactamente esa. Todo lo que hace falta aquí pertenece a la interfaz.
     */
    private final AmqpAdmin amqpAdmin;
    private final ManagementApiClient gestion;
    private final RabbitMQProperties propiedades;

    /**
     * Declara la cola.
     *
     * Declarar es idempotente en AMQP: repetir la petición con los MISMOS argumentos vuelve a
     * responder bien y no duplica nada. Repetirla con argumentos distintos es lo que el broker
     * rechaza con PRECONDITION_FAILED, y el manejador de errores lo traduce a 409.
     */
    public OperacionResultado crear(CrearColaRequest peticion) {
        NombresAmqp.validar(RECURSO, peticion.nombre());

        Queue cola = new Queue(
                peticion.nombre(),
                peticion.durableOPorOmision(),
                peticion.exclusivaOPorOmision(),
                peticion.autoDeleteOPorOmision(),
                peticion.argumentos());

        String declarada = amqpAdmin.declareQueue(cola);
        if (declarada == null) {
            // No debería ocurrir: NombresAmqp ya descartó el caso del prefijo reservado, que es la
            // única razón por la que RabbitAdmin salta una declaración en silencio. Si llegara a
            // pasar, es mejor un 500 ruidoso que un 201 sobre una cola inexistente.
            throw new IllegalStateException(
                    "el broker no confirmó la declaración de la cola " + peticion.nombre());
        }

        log.info("[Colas] Declarada {} durable={} exclusiva={} autoDelete={} argumentos={}",
                declarada, peticion.durableOPorOmision(), peticion.exclusivaOPorOmision(),
                peticion.autoDeleteOPorOmision(), peticion.argumentos());

        return OperacionResultado.de(RECURSO, declarada, "declarada",
                propiedades.esProtegido(declarada)
                        ? "declarada; el nombre figura en la lista de protegidos"
                        : "declarada");
    }

    /**
     * Lista las colas del vhost.
     *
     * Va por la API HTTP de gestión y no por AMQP porque AMQP no tiene operación de listado: no
     * existe un queue.list en el protocolo. Ver ManagementApiClient.
     */
    public List<ColaResumen> listar() {
        return gestion.colas().stream()
                .map(this::aResumen)
                .sorted(Comparator.comparing(ColaResumen::nombre,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * Describe una sola cola, por AMQP.
     *
     * Deliberadamente NO usa la API de gestión: getQueueInfo va por el puerto 5672, así que esta
     * consulta sigue funcionando cuando el 15672 no es alcanzable. Es la única lectura del servicio
     * que no depende de la API de gestión, y por eso vale la pena tenerla.
     */
    public ColaResumen describir(String nombre) {
        QueueInformation info = amqpAdmin.getQueueInfo(nombre);
        if (info == null) {
            throw new RecursoNoEncontradoException(RECURSO, nombre);
        }
        // Los campos que faltan los conoce solo la API de gestión. Se omiten —el DTO no serializa
        // los nulos— antes que devolver valores inventados.
        return new ColaResumen(info.getName(), info.getMessageCount(), info.getConsumerCount(),
                null, null, null, null, null, null, null, propiedades.esProtegido(nombre));
    }

    /**
     * Elimina la cola.
     *
     * Es irreversible y se lleva los mensajes que tuviera dentro, de ahí el seguro sobre los
     * nombres protegidos.
     */
    public OperacionResultado eliminar(String nombre, boolean forzar) {
        comprobarProteccion(nombre, forzar, "eliminar");

        // deleteQueue devuelve false en lugar de lanzar cuando la cola no existe: RabbitAdmin
        // captura el NOT_FOUND del canal. Por eso aquí el 404 sale de un booleano y no del
        // manejador de errores.
        if (!amqpAdmin.deleteQueue(nombre)) {
            throw new RecursoNoEncontradoException(RECURSO, nombre);
        }

        log.warn("[Colas] Eliminada {}{}", nombre, forzar ? " (forzado)" : "");
        return OperacionResultado.de(RECURSO, nombre, "eliminada",
                "la cola y los mensajes que contenía se eliminaron de forma irreversible");
    }

    /**
     * Vacía la cola sin eliminarla.
     *
     * También pasa por el seguro: purgar q.cmd.email descarta notificaciones pendientes, que para
     * lo que importa es tan destructivo como borrar la cola. El caso legítimo habitual es vaciar
     * una DLQ ya analizada, y para eso basta añadir forzar=true.
     */
    public OperacionResultado purgar(String nombre, boolean forzar) {
        comprobarProteccion(nombre, forzar, "purgar");

        // purgeQueue(String) SÍ lanza cuando la cola no existe, al contrario que deleteQueue: el
        // NOT_FOUND del broker sube como AmqpIOException y el manejador lo traduce a 404.
        int purgados = amqpAdmin.purgeQueue(nombre);

        log.warn("[Colas] Purgada {}: {} mensaje(s){}", nombre, purgados, forzar ? " (forzado)" : "");
        return OperacionResultado.purga(nombre, purgados);
    }

    private void comprobarProteccion(String nombre, boolean forzar, String operacion) {
        if (!forzar && propiedades.esProtegido(nombre)) {
            throw new RecursoProtegidoException(RECURSO, nombre, operacion);
        }
    }

    private ColaResumen aResumen(ManagementApiClient.ColaApi api) {
        return new ColaResumen(
                api.name(),
                api.messages(),
                api.consumers(),
                api.durable(),
                api.exclusive(),
                api.autoDelete(),
                api.arguments() == null ? Map.of() : api.arguments(),
                api.state(),
                api.node(),
                api.policy(),
                propiedades.esProtegido(api.name()));
    }
}
