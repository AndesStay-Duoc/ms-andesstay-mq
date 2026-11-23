package cl.andesstay.mq.dto;

import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
        @Pattern(regexp = Restricciones.CARACTERES_DE_NOMBRE,
                message = Restricciones.MENSAJE_CARACTERES_DE_NOMBRE)
        String nombre,

        Boolean durable,

        /**
         * Solo se admite false u omitido.
         *
         * Una cola exclusiva pertenece a la conexión AMQP que la declara, y aquí esa conexión es la
         * de esta API, no la del consumidor que la va a usar. El resultado es una cola que ningún
         * otro servicio puede consumir (RabbitMQ responde RESOURCE_LOCKED) y que el broker borra,
         * con sus mensajes, en cuanto esta API se reinicia o pierde la conexión. No hay ningún uso
         * legítimo de eso en una API de administración, así que se rechaza con un 400 en vez de
         * crear algo que va a desaparecer. Se conserva el campo para que el rechazo sea explícito.
         */
        @AssertFalse(message = "no se admite una cola exclusiva: pertenecería a la conexión de esta "
                + "API, ningún otro servicio podría consumirla y el broker la borraría al reiniciarse "
                + "la API")
        Boolean exclusiva,

        Boolean autoDelete,

        /**
         * Argumentos de la cola: x-message-ttl, x-max-length, x-dead-letter-exchange,
         * x-queue-type... Se validan contra una lista cerrada, con su tipo y su rango, antes de
         * llegar al broker; ver service/ArgumentosAmqp para el porqué.
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
