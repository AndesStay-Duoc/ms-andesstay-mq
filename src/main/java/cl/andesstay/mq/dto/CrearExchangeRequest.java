package cl.andesstay.mq.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Petición de alta de un exchange. */
public record CrearExchangeRequest(

        @NotBlank(message = "el nombre del exchange es obligatorio")
        @Size(max = 255, message = "el nombre no puede pasar de 255 caracteres")
        String nombre,

        @NotNull(message = "el tipo es obligatorio: DIRECT, TOPIC, FANOUT o HEADERS")
        TipoExchange tipo,

        Boolean durable,
        Boolean autoDelete,
        Map<String, Object> argumentos
) {

    public boolean durableOPorOmision() {
        return durable == null || durable;
    }

    public boolean autoDeleteOPorOmision() {
        return autoDelete != null && autoDelete;
    }
}
