package cl.andesstay.mq.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Respuesta de las operaciones de escritura.
 *
 * Existe para que un 201 o un 200 digan QUÉ pasó y no solo que no hubo error. En particular,
 * `creado` distingue el alta real de la redeclaración idempotente: declarar una cola que ya existe
 * con los mismos argumentos es una operación válida en AMQP y el broker responde con un OK, así
 * que sin este campo las dos cosas serían indistinguibles para quien llama.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperacionResultado(
        String recurso,
        String nombre,
        String operacion,
        String detalle,
        /** Número de mensajes eliminados. Solo lo rellena la purga. */
        Integer mensajesPurgados
) {

    public static OperacionResultado de(String recurso, String nombre, String operacion, String detalle) {
        return new OperacionResultado(recurso, nombre, operacion, detalle, null);
    }

    public static OperacionResultado purga(String nombre, int mensajes) {
        return new OperacionResultado("cola", nombre, "purgada",
                mensajes + " mensaje(s) eliminado(s)", mensajes);
    }
}
