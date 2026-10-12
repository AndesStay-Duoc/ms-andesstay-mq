package cl.andesstay.mq.dto;

import org.springframework.amqp.core.Binding;

/**
 * A qué se enlaza un exchange: a una cola o a otro exchange.
 *
 * AMQP permite las dos cosas, y la segunda es la que hace posible encadenar exchanges, así que la
 * API no la esconde.
 *
 * Es un enum propio y no el DestinationType de Spring AMQP a propósito: este es el contrato público
 * de la API y no debería cambiar porque Spring renombre el suyo. Los nombres coinciden hoy, así que
 * la traducción es directa, y está en un solo sitio.
 */
public enum TipoDestino {

    QUEUE,
    EXCHANGE;

    public Binding.DestinationType aTipoAmqp() {
        return Binding.DestinationType.valueOf(name());
    }
}
