package cl.andesstay.mq.service;

import java.nio.charset.StandardCharsets;

/**
 * Validación de nombres antes de hablar con el broker.
 *
 * Existe por un comportamiento concreto de Spring AMQP que de otro modo produciría una respuesta
 * falsa: RabbitAdmin SALTA en silencio toda cola o exchange cuyo nombre empiece por «amq.», deja
 * una línea en el log a nivel DEBUG y no declara nada. En declareQueue eso devuelve null, y en
 * declareExchange no devuelve nada en absoluto, así que sin esta comprobación la API responderia
 * 201 sobre un recurso que nunca se creó.
 *
 * El prefijo está reservado por el propio protocolo AMQP 0-9-1 para los recursos que genera el
 * broker, así que rechazarlo con un 400 es además la respuesta correcta.
 */
final class NombresAmqp {

    /** Reservado por AMQP 0-9-1 para los recursos que crea el broker. */
    private static final String PREFIJO_RESERVADO = "amq.";

    /** El límite es del protocolo: los nombres viajan en un short string de 255 bytes. */
    private static final int LARGO_MAXIMO = 255;

    private NombresAmqp() {
    }

    static void validar(String recurso, String nombre) {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("el nombre del recurso " + recurso + " es obligatorio");
        }
        if (nombre.startsWith(PREFIJO_RESERVADO)) {
            throw new IllegalArgumentException("el prefijo " + PREFIJO_RESERVADO
                    + " está reservado por AMQP para los recursos que genera el broker; "
                    + "elige otro nombre");
        }
        // Se miden BYTES y no caracteres: un nombre con acentos ocupa más de lo que aparenta, y el
        // límite del protocolo es en bytes.
        if (nombre.getBytes(StandardCharsets.UTF_8).length > LARGO_MAXIMO) {
            throw new IllegalArgumentException(
                    "el nombre no puede pasar de " + LARGO_MAXIMO + " bytes");
        }
    }
}
