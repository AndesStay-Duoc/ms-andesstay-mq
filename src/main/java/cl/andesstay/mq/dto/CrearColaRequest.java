package cl.andesstay.mq.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Petición de alta de una cola.
 *
 * Los tres booleanos son objetos y no primitivos a propósito: así se distingue «no lo envié» de
 * «lo envié en false», y los omitidos toman el valor por omisión razonable en lugar de false.
 * Una cola durable por omisión es lo correcto en este sistema; con un boolean primitivo, omitir
 * el campo habría creado una cola que desaparece al reiniciar el broker.
 */
public record CrearColaRequest(

        /** El límite de 255 bytes es del protocolo AMQP, no una decisión de esta API. */
        @NotBlank(message = "el nombre de la cola es obligatorio")
        @Size(max = 255, message = "el nombre no puede pasar de 255 caracteres")
        String nombre,

        Boolean durable,
        Boolean exclusiva,
        Boolean autoDelete,

        /**
         * Argumentos de la cola: x-message-ttl, x-max-length, x-dead-letter-exchange,
         * x-queue-type... Se pasan tal cual al broker, que es quien valida los nombres y los
         * rangos; replicar aquí su tabla de argumentos sería una copia condenada a quedarse atrás.
         */
        Map<String, Object> argumentos
) {

    public boolean durableOPorOmision() {
        return durable == null || durable;
    }

    public boolean exclusivaOPorOmision() {
        return exclusiva != null && exclusiva;
    }

    public boolean autoDeleteOPorOmision() {
        return autoDelete != null && autoDelete;
    }
}
