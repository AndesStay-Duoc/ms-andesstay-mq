package cl.andesstay.mq.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Lo que la API devuelve de un exchange.
 *
 * `tipo` llega como texto y no como TipoExchange porque el broker puede tener exchanges de plugin
 * —x-delayed-message, x-consistent-hash— que este servicio no sabe crear pero sí tiene que poder
 * listar. Forzarlos al enum los haría desaparecer de la respuesta.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeResumen(
        String nombre,
        String tipo,
        Boolean durable,
        Boolean autoDelete,
        Boolean interno,
        Map<String, Object> argumentos,
        boolean protegido
) {
}
