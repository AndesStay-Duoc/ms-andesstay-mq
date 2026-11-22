package cl.andesstay.mq.service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Validación de los argumentos de colas, exchanges y bindings antes de hablar con el broker.
 *
 * POR QUE EXISTE. Antes el mapa se pasaba tal cual a RabbitMQ, y eso tenía dos consecuencias que la
 * API no podía explicar:
 *
 *   - Un argumento con el tipo o el rango equivocado ("x-message-ttl": "60000", o un x-max-length
 *     negativo) lo rechaza el broker con PRECONDITION_FAILED. Es el mismo código que usa para una
 *     redeclaración incompatible, así que llegaba al cliente como 409 TOPOLOGIA_INCOMPATIBLE: un
 *     conflicto con lo que ya existe, cuando en realidad la petición estaba mal escrita.
 *   - Un argumento desconocido —típicamente un error de tipeo, "x-mesage-ttl"— el broker lo ACEPTA,
 *     lo guarda y no hace nada con él. La API respondía 201 sobre una cola sin el TTL que se pidió,
 *     y nadie se enteraba hasta que los mensajes no caducaban.
 *
 * Con esta validación los dos casos son un 400 que nombra el argumento y dice qué se esperaba.
 *
 * POR QUE UNA LISTA CERRADA. La tabla de argumentos de RabbitMQ es corta y cambia poco, y esta es una
 * API de administración de una topología concreta, no un cliente genérico. Cerrarla es lo que hace
 * visible el error de tipeo. Si hiciera falta un argumento nuevo, se agrega aquí con su regla.
 *
 * LOS BINDINGS SON DISTINTOS. En un exchange headers, las claves del binding son los nombres de las
 * cabeceras con que se compara el mensaje: pueden ser cualquiera. Por eso ahí solo se valida x-match,
 * que es el único argumento que interpreta RabbitMQ.
 *
 * Los rangos siguen la documentación de RabbitMQ: los tiempos y tamaños son enteros sin signo, y
 * x-max-priority va de 1 a 255.
 */
final class ArgumentosAmqp {

    /** Mayor entero de 32 bits sin signo: el techo de los tiempos en milisegundos de RabbitMQ. */
    private static final long MAX_UINT32 = 4_294_967_295L;
    private static final int LARGO_MAXIMO_NOMBRE = 255;

    /** Una regla devuelve null si el valor sirve, o una descripción de lo que se esperaba. */
    @FunctionalInterface
    private interface Regla {
        String problema(Object valor);
    }

    private static final Map<String, Regla> DE_COLA = new TreeMap<>(Map.ofEntries(
            Map.entry("x-message-ttl", entero(0, MAX_UINT32)),
            Map.entry("x-expires", entero(1, MAX_UINT32)),
            Map.entry("x-max-length", entero(0, Long.MAX_VALUE)),
            Map.entry("x-max-length-bytes", entero(0, Long.MAX_VALUE)),
            Map.entry("x-overflow", unoDe("drop-head", "reject-publish", "reject-publish-dlx")),
            Map.entry("x-dead-letter-exchange", nombre()),
            Map.entry("x-dead-letter-routing-key", texto()),
            Map.entry("x-dead-letter-strategy", unoDe("at-most-once", "at-least-once")),
            Map.entry("x-queue-type", unoDe("classic", "quorum", "stream")),
            Map.entry("x-max-priority", entero(1, 255)),
            Map.entry("x-single-active-consumer", booleano()),
            Map.entry("x-delivery-limit", entero(0, Integer.MAX_VALUE)),
            Map.entry("x-queue-mode", unoDe("default", "lazy"))));

    private static final Map<String, Regla> DE_EXCHANGE = new TreeMap<>(Map.of(
            "alternate-exchange", nombre()));

    private static final Regla X_MATCH = unoDe("all", "any", "all-with-x", "any-with-x");

    /** Los tipos de cola que el broker solo acepta durables. */
    private static final Set<String> TIPOS_SOLO_DURABLES = Set.of("quorum", "stream");

    private ArgumentosAmqp() {
    }

    static void validarCola(Map<String, Object> argumentos, boolean durable) {
        validarContra("la cola", DE_COLA, argumentos);

        Object tipo = argumentos == null ? null : argumentos.get("x-queue-type");
        if (tipo != null && TIPOS_SOLO_DURABLES.contains(tipo) && !durable) {
            throw new IllegalArgumentException("una cola x-queue-type=" + tipo
                    + " tiene que ser durable: RabbitMQ no las admite de otro modo");
        }
    }

    static void validarExchange(Map<String, Object> argumentos) {
        validarContra("el exchange", DE_EXCHANGE, argumentos);
    }

    static void validarBinding(Map<String, Object> argumentos) {
        if (argumentos == null) {
            return;
        }
        argumentos.forEach((clave, valor) -> {
            if (valor == null) {
                throw new IllegalArgumentException("el argumento '" + clave + "' del binding no tiene valor");
            }
        });
        if (argumentos.containsKey("x-match")) {
            String problema = X_MATCH.problema(argumentos.get("x-match"));
            if (problema != null) {
                throw new IllegalArgumentException("el argumento 'x-match' del binding " + problema);
            }
        }
    }

    private static void validarContra(String recurso, Map<String, Regla> reglas, Map<String, Object> argumentos) {
        if (argumentos == null) {
            return;
        }
        argumentos.forEach((clave, valor) -> {
            Regla regla = reglas.get(clave);
            if (regla == null) {
                throw new IllegalArgumentException("argumento desconocido '" + clave + "' para " + recurso
                        + ". RabbitMQ lo aceptaría sin aplicarlo; los admitidos son: "
                        + String.join(", ", reglas.keySet()));
            }
            String problema = valor == null ? "no tiene valor" : regla.problema(valor);
            if (problema != null) {
                throw new IllegalArgumentException("el argumento '" + clave + "' de " + recurso + " " + problema);
            }
        });
    }

    // ── Reglas ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Un entero dentro del rango. Se exige número entero y no texto ni decimal: Jackson entrega los
     * números JSON enteros como Integer o Long, y "60000" entre comillas es un error del cliente que
     * el broker rechazaría igual.
     */
    private static Regla entero(long minimo, long maximo) {
        return valor -> {
            if (!(valor instanceof Integer || valor instanceof Long
                    || valor instanceof Short || valor instanceof Byte)) {
                return "tiene que ser un número entero, no " + descripcion(valor);
            }
            long n = ((Number) valor).longValue();
            if (n < minimo || n > maximo) {
                return "tiene que estar entre " + minimo + " y " + maximo + ", no " + n;
            }
            return null;
        };
    }

    private static Regla texto() {
        return valor -> valor instanceof String ? null : "tiene que ser texto, no " + descripcion(valor);
    }

    /** El nombre de un exchange: texto de hasta 255 bytes, el límite de AMQP. La cadena vacía es el exchange por defecto. */
    private static Regla nombre() {
        return valor -> {
            if (!(valor instanceof String s)) {
                return "tiene que ser el nombre de un exchange (texto), no " + descripcion(valor);
            }
            return s.getBytes(StandardCharsets.UTF_8).length > LARGO_MAXIMO_NOMBRE
                    ? "no puede pasar de " + LARGO_MAXIMO_NOMBRE + " bytes"
                    : null;
        };
    }

    private static Regla booleano() {
        return valor -> valor instanceof Boolean ? null : "tiene que ser true o false, no " + descripcion(valor);
    }

    private static Regla unoDe(String... admitidos) {
        Set<String> conjunto = new TreeSet<>(Set.of(admitidos));
        return valor -> valor instanceof String s && conjunto.contains(s)
                ? null
                : "tiene que ser uno de " + conjunto + ", no " + descripcion(valor);
    }

    private static String descripcion(Object valor) {
        return valor instanceof String s ? "\"" + s + "\"" : String.valueOf(valor);
    }
}
