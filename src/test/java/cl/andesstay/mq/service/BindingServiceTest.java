package cl.andesstay.mq.service;

import cl.andesstay.mq.broker.ManagementApiClient;
import cl.andesstay.mq.dto.CrearBindingRequest;
import cl.andesstay.mq.dto.TipoDestino;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Las dos trampas del recurso de bindings.
 *
 * La primera es el orden de los argumentos del constructor de Binding, que es
 * (destino, tipo, exchange, routingKey): el destino va primero y el origen tercero, al contrario de
 * como se lee la regla. Equivocarlo compila sin un aviso y produce un binding al revés, que no
 * entrega nada y no da ningún error.
 *
 * La segunda es el exchange por omisión, que tiene bindings implícitos a cada cola por su nombre y
 * no admite que se desenlacen.
 */
@ExtendWith(MockitoExtension.class)
class BindingServiceTest {

    @Mock
    private AmqpAdmin amqpAdmin;
    @Mock
    private ManagementApiClient gestion;

    @InjectMocks
    private BindingService servicio;

    @Test
    @DisplayName("el origen y el destino no se intercambian al construir el Binding")
    void origenYDestinoEnSuSitio() {
        servicio.crear(new CrearBindingRequest(
                "cmd.direct", "q.cmd.email", TipoDestino.QUEUE, "email.send", null));

        ArgumentCaptor<Binding> declarado = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).declareBinding(declarado.capture());

        Binding binding = declarado.getValue();
        // Si estos dos se invirtieran, el binding iría de la cola al exchange: no entregaría nada
        // y el broker no se quejaría.
        assertEquals("cmd.direct", binding.getExchange(), "el exchange es el ORIGEN");
        assertEquals("q.cmd.email", binding.getDestination(), "la cola es el DESTINO");
        assertEquals("email.send", binding.getRoutingKey());
        assertEquals(Binding.DestinationType.QUEUE, binding.getDestinationType());
    }

    @Test
    @DisplayName("el destino puede ser otro exchange, que es lo que permite encadenarlos")
    void destinoPuedeSerExchange() {
        servicio.crear(new CrearBindingRequest(
                "cmd.topic", "cmd.direct", TipoDestino.EXCHANGE, "email.#", null));

        ArgumentCaptor<Binding> declarado = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).declareBinding(declarado.capture());

        assertEquals(Binding.DestinationType.EXCHANGE, declarado.getValue().getDestinationType());
    }

    @Test
    @DisplayName("sin tipoDestino se asume QUEUE, que es el caso habitual")
    void tipoDestinoPorOmisionEsQueue() {
        servicio.crear(new CrearBindingRequest(
                "cmd.direct", "q.cmd.voucher", null, "voucher.gen", null));

        ArgumentCaptor<Binding> declarado = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).declareBinding(declarado.capture());

        assertEquals(Binding.DestinationType.QUEUE, declarado.getValue().getDestinationType());
    }

    @Test
    @DisplayName("una routing key ausente se normaliza a cadena vacía, no a null")
    void routingKeyAusenteEsCadenaVacia() {
        // Un fanout no usa routing key, así que omitirla es legítimo. Pasar null al broker sí
        // sería un error.
        servicio.crear(new CrearBindingRequest(
                "cmd.fanout", "q.pruebas.demo", TipoDestino.QUEUE, null, null));

        ArgumentCaptor<Binding> declarado = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).declareBinding(declarado.capture());

        assertEquals("", declarado.getValue().getRoutingKey());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("no se puede desenlazar del exchange por omisión")
    void exchangePorOmisionNoSeDesenlaza(String exchange) {
        // Sus bindings son implícitos: el broker los mantiene para cada cola por su nombre y no
        // son reglas declaradas, así que no hay nada que quitar. Sin esta comprobación el broker
        // devolvería un error bastante menos claro.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> servicio.eliminar(exchange, "q.cmd.email", TipoDestino.QUEUE, ""));

        assertTrue(e.getMessage().contains("implícitos"),
                "el mensaje tiene que explicar por qué: " + e.getMessage());
        verify(amqpAdmin, never()).removeBinding(any());
    }

    @Test
    @DisplayName("eliminar un binding normal sí llega al broker")
    void eliminarBindingNormal() {
        servicio.eliminar("cmd.direct", "q.cmd.email", TipoDestino.QUEUE, "email.send");

        ArgumentCaptor<Binding> quitado = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).removeBinding(quitado.capture());

        assertEquals("cmd.direct", quitado.getValue().getExchange());
        assertEquals("q.cmd.email", quitado.getValue().getDestination());
    }
}
