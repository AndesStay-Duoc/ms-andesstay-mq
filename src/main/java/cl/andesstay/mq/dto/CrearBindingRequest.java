package cl.andesstay.mq.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Petición de alta de un binding.
 *
 * El destino puede ser una cola o otro exchange: AMQP permite las dos cosas y la segunda es la que
 * hace posible encadenar exchanges, así que la API no la esconde.
 */
public record CrearBindingRequest(

        @NotBlank(message = "el exchange de origen es obligatorio")
        String exchange,

        @NotBlank(message = "el destino es obligatorio")
        String destino,

        /** QUEUE si se omite, que es el caso habitual. */
        TipoDestino tipoDestino,

        /**
         * Sin validar como obligatoria: un binding a un exchange fanout no usa routing key, y la
         * cadena vacía es un valor legítimo. Se normaliza a "" cuando llega null.
         */
        String routingKey,

        /** Necesarios para un exchange de tipo HEADERS, donde la coincidencia va por x-match. */
        Map<String, Object> argumentos
) {

    public TipoDestino tipoDestinoOPorOmision() {
        return tipoDestino == null ? TipoDestino.QUEUE : tipoDestino;
    }

    public String routingKeyOVacia() {
        return routingKey == null ? "" : routingKey;
    }
}
