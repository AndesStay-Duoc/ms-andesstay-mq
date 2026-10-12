package cl.andesstay.mq.dto;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.HeadersExchange;
import org.springframework.amqp.core.TopicExchange;

import java.util.Map;

/**
 * Los cuatro tipos de exchange de AMQP 0-9-1.
 *
 * Se modela como enum y no como String libre para que un tipo mal escrito se rechace con un 400 en
 * la deserialización, antes de tocar el broker. Con un String, "diract" llegaría a RabbitMQ y
 * volvería como un error de canal cerrado, mucho más difícil de leer para quien llama.
 */
public enum TipoExchange {

    /** Entrega por coincidencia exacta de routing key. Es el de cmd.direct. */
    DIRECT(ExchangeTypes.DIRECT),

    /** Entrega por patrón con * y #. Es el de cmd.topic. */
    TOPIC(ExchangeTypes.TOPIC),

    /** Entrega a todas las colas enlazadas, ignorando la routing key. */
    FANOUT(ExchangeTypes.FANOUT),

    /** Entrega por coincidencia de cabeceras en lugar de routing key. */
    HEADERS(ExchangeTypes.HEADERS);

    private final String nombreAmqp;

    TipoExchange(String nombreAmqp) {
        this.nombreAmqp = nombreAmqp;
    }

    public String nombreAmqp() {
        return nombreAmqp;
    }

    /** Construye el Exchange de Spring AMQP que corresponde a este tipo. */
    public Exchange construir(String nombre, boolean durable, boolean autoDelete,
                              Map<String, Object> argumentos) {
        return switch (this) {
            case DIRECT -> new DirectExchange(nombre, durable, autoDelete, argumentos);
            case TOPIC -> new TopicExchange(nombre, durable, autoDelete, argumentos);
            case FANOUT -> new FanoutExchange(nombre, durable, autoDelete, argumentos);
            case HEADERS -> new HeadersExchange(nombre, durable, autoDelete, argumentos);
        };
    }

    /**
     * Resuelve el tipo que informa la API de gestión. Devuelve null si el broker reporta un tipo
     * que este enum no conoce, lo que ocurre con los exchanges de plugin (x-delayed-message,
     * x-consistent-hash): existen en el broker y hay que poder listarlos, aunque esta API no los
     * sepa crear.
     */
    public static TipoExchange desdeNombreAmqp(String valor) {
        if (valor == null) {
            return null;
        }
        for (TipoExchange tipo : values()) {
            if (tipo.nombreAmqp.equalsIgnoreCase(valor)) {
                return tipo;
            }
        }
        return null;
    }
}
