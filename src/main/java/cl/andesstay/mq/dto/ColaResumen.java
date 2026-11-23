package cl.andesstay.mq.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Lo que la API devuelve de una cola.
 *
 * Es un subconjunto deliberado de lo que informa la API de gestión, que son más de cuarenta campos
 * por cola entre tasas, deltas y detalles del nodo. Aquí quedan los que sirven para administrar:
 * cuánto hay dentro, quién escucha, si sobrevive al reinicio y con qué argumentos se declaró.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ColaResumen(
        String nombre,
        Integer mensajes,
        Integer consumidores,
        Boolean durable,
        Boolean exclusiva,
        Boolean autoDelete,
        Map<String, Object> argumentos,
        String estado,
        String nodo,
        /** Nombre de la política aplicada, si alguna. Es lo que delata el espejado (ver QueueService). */
        String politica,
        /** True si el nombre está en la lista de protegidos de la configuración. */
        boolean protegida
) {
}
