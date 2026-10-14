package cl.andesstay.mq.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Lo que la API devuelve de un binding.
 *
 * El exchange por omisión aparece con nombre vacío, y así se deja: es como lo informa el broker, y
 * esos bindings implícitos no se pueden crear ni borrar. Verlos explica por qué una cola recibe
 * mensajes publicados con su propio nombre como routing key.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BindingResumen(
        String exchange,
        String destino,
        String tipoDestino,
        String routingKey,
        Map<String, Object> argumentos
) {
}
