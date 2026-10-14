package cl.andesstay.mq.web;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Traduce los errores del broker a códigos HTTP que signifiquen algo para quien llama.
 *
 * Es lo que separa una API de administración usable de una que devuelve 500 en sus dos casos más
 * normales. Los dos casos son:
 *
 *   redeclarar una cola con argumentos distintos  -> el broker responde PRECONDITION_FAILED -> 409
 *   operar sobre algo que no existe               -> el broker responde NOT_FOUND            -> 404
 *
 * Sin esta clase los dos salen como 500 con una traza de Spring, y quien integra no tiene forma de
 * distinguir «tu petición choca con lo que ya hay» de «el servicio se cayó».
 */
@Slf4j
@RestControllerAdvice
public class ManejadorDeErrores {

    /** Códigos de respuesta de AMQP 0-9-1. Son del protocolo, no de RabbitMQ. */
    private static final int AMQP_CONTENT_TOO_LARGE = 311;
    private static final int AMQP_NO_ROUTE = 312;
    private static final int AMQP_ACCESS_REFUSED = 403;
    private static final int AMQP_NOT_FOUND = 404;
    private static final int AMQP_RESOURCE_LOCKED = 405;
    private static final int AMQP_PRECONDITION_FAILED = 406;

    // ── Errores de negocio ───────────────────────────────────────────────

    /** 404 · No existe. */
    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<Map<String, Object>> noEncontrado(RecursoNoEncontradoException e) {
        return cuerpo(HttpStatus.NOT_FOUND, "RECURSO_NO_ENCONTRADO", e.getMessage(),
                Map.of("recurso", e.getRecurso(), "nombre", e.getNombre()));
    }

    /** 409 · Es un recurso de la topología de AndesStay y no se confirmó con ?forzar=true. */
    @ExceptionHandler(RecursoProtegidoException.class)
    public ResponseEntity<Map<String, Object>> protegido(RecursoProtegidoException e) {
        log.warn("[Protección] Se rechazó una operación destructiva sobre {} sin forzar=true",
                e.getNombre());
        return cuerpo(HttpStatus.CONFLICT, "RECURSO_PROTEGIDO", e.getMessage(),
                Map.of("nombre", e.getNombre()));
    }

    /** 400 · Nombre reservado, tipo inexistente, rango fuera de lo admitido. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> peticionInvalida(IllegalArgumentException e) {
        return cuerpo(HttpStatus.BAD_REQUEST, "PETICION_INVALIDA", e.getMessage(), Map.of());
    }

    /** 400 · Falló la validación del cuerpo. Se devuelve campo por campo. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validacion(MethodArgumentNotValidException e) {
        Map<String, Object> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(f -> campos.put(f.getField(), f.getDefaultMessage()));
        return cuerpo(HttpStatus.BAD_REQUEST, "VALIDACION", "el cuerpo no es válido",
                Map.of("campos", campos));
    }

    /**
     * 400 · JSON mal formado, o un tipo que no es ninguno de los cuatro del enum.
     *
     * Se trata aparte de la validación porque ocurre ANTES: Jackson falla al construir el objeto,
     * así que las anotaciones de jakarta.validation nunca llegan a evaluarse.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> cuerpoIlegible(HttpMessageNotReadableException e) {
        return cuerpo(HttpStatus.BAD_REQUEST, "CUERPO_ILEGIBLE",
                "el cuerpo no se pudo interpretar; revisa el JSON y el valor de tipo",
                Map.of("detalle", String.valueOf(e.getMostSpecificCause().getMessage())));
    }

    /** 403 · Autenticado pero sin el rol Admin. Nunca 500. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> sinPermiso(AccessDeniedException e) {
        return cuerpo(HttpStatus.FORBIDDEN, "ROL_INSUFICIENTE",
                "esta operación exige el rol Admin", Map.of());
    }

    // ── Errores del broker ───────────────────────────────────────────────

    /**
     * 503 · El broker no está.
     *
     * Se declara aparte de AmqpException aunque sean subclases suyas: Spring elige el
     * @ExceptionHandler más específico, y tenerlo escrito deja clara la intención de que un broker
     * caído no se confunda con un broker que rechaza.
     */
    @ExceptionHandler({AmqpConnectException.class, AmqpTimeoutException.class})
    public ResponseEntity<Map<String, Object>> brokerCaido(AmqpException e) {
        log.error("[Broker] Sin conexión AMQP: {}", e.getMessage());
        return cuerpo(HttpStatus.SERVICE_UNAVAILABLE, "BROKER_NO_DISPONIBLE",
                "no hay conexión con RabbitMQ", Map.of());
    }

    /**
     * El resto de los fallos de AMQP, clasificados por el código con el que el broker cerró el
     * canal.
     *
     * Hay que recorrer la cadena de causas porque Spring envuelve el error dos veces: la
     * ShutdownSignalException del cliente de RabbitMQ viaja dentro de una IOException y esa dentro
     * de una AmqpIOException. El código vive en el reason de la señal, no en ningún mensaje.
     */
    @ExceptionHandler(AmqpException.class)
    public ResponseEntity<Map<String, Object>> errorDelBroker(AmqpException e) {
        CierreAmqp cierre = cierreAmqp(e);

        if (cierre == null) {
            log.error("[Broker] Error AMQP sin código de cierre de canal", e);
            return cuerpo(HttpStatus.BAD_GATEWAY, "ERROR_DEL_BROKER",
                    "el broker rechazó la operación",
                    Map.of("detalle", String.valueOf(e.getMessage())));
        }

        int codigo = cierre.codigo();
        String motivo = cierre.texto();
        log.warn("[Broker] El broker cerró el canal con {}: {}", codigo, motivo);
        return switch (codigo) {
            // 406. El caso clásico: la cola ya existe con otros argumentos y AMQP no permite
            // redefinirla. El mensaje del broker nombra el argumento concreto que difiere, que es
            // más útil que cualquier texto propio, así que se devuelve tal cual.
            case AMQP_PRECONDITION_FAILED -> cuerpo(HttpStatus.CONFLICT, "TOPOLOGIA_INCOMPATIBLE",
                    "ya existe con una definición distinta y AMQP no permite redefinirla",
                    Map.of("detalleDelBroker", motivo));

            // 404. Borrar o purgar algo inexistente.
            case AMQP_NOT_FOUND -> cuerpo(HttpStatus.NOT_FOUND, "RECURSO_NO_ENCONTRADO",
                    "el broker no encontró el recurso", Map.of("detalleDelBroker", motivo));

            // 405. Una cola exclusiva pertenece a la conexión que la creó; nadie más la toca.
            case AMQP_RESOURCE_LOCKED -> cuerpo(HttpStatus.CONFLICT, "RECURSO_BLOQUEADO",
                    "el recurso está en uso exclusivo por otra conexión",
                    Map.of("detalleDelBroker", motivo));

            // 403. Las credenciales del servicio no tienen permiso sobre el vhost. No es culpa de
            // quien llama: es configuración de este servicio, de ahí el 502 y no un 403.
            case AMQP_ACCESS_REFUSED -> cuerpo(HttpStatus.BAD_GATEWAY, "BROKER_RECHAZO_ACCESO",
                    "las credenciales del servicio no tienen permiso sobre el vhost",
                    Map.of("detalleDelBroker", motivo));

            case AMQP_NO_ROUTE, AMQP_CONTENT_TOO_LARGE -> cuerpo(HttpStatus.BAD_GATEWAY,
                    "ERROR_DEL_BROKER", "el broker rechazó la operación",
                    Map.of("codigoAmqp", codigo, "detalleDelBroker", motivo));

            default -> cuerpo(HttpStatus.BAD_GATEWAY, "ERROR_DEL_BROKER",
                    "el broker rechazó la operación",
                    Map.of("codigoAmqp", codigo, "detalleDelBroker", motivo));
        };
    }

    /** 503 o 502 · según si la API de gestión contestó algo o no contestó en absoluto. */
    @ExceptionHandler(ApiDeGestionException.class)
    public ResponseEntity<Map<String, Object>> apiDeGestion(ApiDeGestionException e) {
        if (!e.esAlcanzable()) {
            return cuerpo(HttpStatus.SERVICE_UNAVAILABLE, "GESTION_NO_DISPONIBLE",
                    "no se alcanzó la API de gestión del broker en el puerto 15672. "
                            + "El listado la necesita: AMQP no tiene operación de listado.",
                    Map.of());
        }
        return cuerpo(HttpStatus.BAD_GATEWAY, "GESTION_RECHAZO",
                e.getMessage() + ". Comprueba que el usuario tenga la etiqueta administrator "
                        + "y que el vhost configurado exista.",
                Map.of());
    }

    // ── Apoyo ────────────────────────────────────────────────────────────

    /**
     * Lo que el broker dice al cerrar un canal: su código de respuesta y su texto.
     *
     * El texto importa tanto como el código. Ante un 406, el broker nombra el argumento concreto
     * que difiere, y eso es más útil para quien integra que cualquier mensaje que se escriba aquí.
     */
    record CierreAmqp(int codigo, String texto) {
    }

    /**
     * Busca el cierre con el que el broker rechazó la operación.
     *
     * Hay que recorrer la cadena de causas porque Spring envuelve el error dos veces: la
     * ShutdownSignalException del cliente de RabbitMQ viaja dentro de una IOException y esa dentro
     * de una AmqpIOException. Devuelve null si no hay ninguna señal en la cadena, que es lo que
     * ocurre cuando el fallo es del lado del cliente y no del broker.
     *
     * El corte por causa cíclica no debería hacer falta nunca, pero una cadena que se referencia a
     * sí misma colgaría el bucle, y comprobarlo cuesta una línea.
     */
    static CierreAmqp cierreAmqp(Throwable error) {
        for (Throwable causa = error; causa != null; causa = causa.getCause()) {
            if (causa instanceof ShutdownSignalException senal) {
                Object razon = senal.getReason();
                if (razon instanceof AMQP.Channel.Close cierre) {
                    return new CierreAmqp(cierre.getReplyCode(), cierre.getReplyText());
                }
                if (razon instanceof AMQP.Connection.Close cierre) {
                    return new CierreAmqp(cierre.getReplyCode(), cierre.getReplyText());
                }
            }
            if (causa.getCause() == causa) {
                break;
            }
        }
        return null;
    }

    /**
     * No se captura Exception a secas a propósito: un fallo inesperado tiene que seguir siendo un
     * 500 con su traza en el log. Convertir todo en 4xx escondería defectos reales.
     */
    private static ResponseEntity<Map<String, Object>> cuerpo(
            HttpStatus estado, String error, String detalle, Map<String, Object> extra) {
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("error", error);
        respuesta.put("detalle", detalle);
        respuesta.putAll(extra);
        return ResponseEntity.status(estado).body(respuesta);
    }
}
