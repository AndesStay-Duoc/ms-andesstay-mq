package cl.andesstay.mq.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;
import java.util.Map;

/**
 * Petición de alta de un binding.
 *
 * El destino puede ser una cola o otro exchange: AMQP permite las dos cosas y la segunda es la que
 * hace posible encadenar exchanges, así que la API no la esconde.
 */
public record CrearBindingRequest(

        @NotBlank(message = "el exchange de origen es obligatorio")
        @Size(max = Restricciones.LARGO_MAXIMO, message = Restricciones.MENSAJE_LARGO)
        @Pattern(regexp = Restricciones.CARACTERES_DE_NOMBRE,
                message = Restricciones.MENSAJE_CARACTERES_DE_NOMBRE)
        String exchange,

        @NotBlank(message = "el destino es obligatorio")
        @Size(max = Restricciones.LARGO_MAXIMO, message = Restricciones.MENSAJE_LARGO)
        @Pattern(regexp = Restricciones.CARACTERES_DE_NOMBRE,
                message = Restricciones.MENSAJE_CARACTERES_DE_NOMBRE)
        String destino,

        /**
         * QUEUE si se omite, que es el caso habitual.
         *
         * Llega como texto y no como TipoDestino para que un valor desconocido salga como un error
         * de validación del campo, junto a los demás, y no como un cuerpo ilegible: Jackson falla
         * al construir el enum antes de que se evalúe ninguna anotación. En el JSON los valores
         * siguen siendo los mismos.
         */
        @Pattern(regexp = Restricciones.TIPOS_DESTINO, message = Restricciones.MENSAJE_TIPOS_DESTINO)
        String tipoDestino,

        /**
         * Sin validar como obligatoria: un binding a un exchange fanout no usa routing key, y la
         * cadena vacía es un valor legítimo. Se normaliza a "" cuando llega null.
         */
        @Size(max = Restricciones.LARGO_MAXIMO, message = Restricciones.MENSAJE_LARGO)
        @Pattern(regexp = Restricciones.ROUTING_KEY, message = Restricciones.MENSAJE_ROUTING_KEY)
        String routingKey,

        /** Necesarios para un exchange de tipo HEADERS, donde la coincidencia va por x-match. */
        Map<String, Object> argumentos
) {

    /** La validación ya garantiza uno de los dos valores; aquí solo se traduce al enum. */
    public TipoDestino tipoDestinoOPorOmision() {
        return tipoDestino == null
                ? TipoDestino.QUEUE
                : TipoDestino.valueOf(tipoDestino.toUpperCase(Locale.ROOT));
    }

    public String routingKeyOVacia() {
        return routingKey == null ? "" : routingKey;
    }
}
