package cl.andesstay.mq.service;

import cl.andesstay.mq.broker.ManagementApiClient;
import cl.andesstay.mq.config.RabbitMQProperties;
import cl.andesstay.mq.dto.CrearBindingRequest;
import cl.andesstay.mq.dto.TipoDestino;
import cl.andesstay.mq.web.RecursoNoEncontradoException;
import cl.andesstay.mq.web.RecursoProtegidoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Las trampas del recurso de bindings.
 *
 * La primera es el orden de los argumentos del constructor de Binding, que es
 * (destino, tipo, exchange, routingKey): el destino va primero y el origen tercero, al contrario de
 * como se lee la regla. Equivocarlo compila sin un aviso y produce un binding al revés, que no
 * entrega nada y no da ningún error.
 *
 * La segunda es el exchange por omisión, que tiene bindings implícitos a cada cola por su nombre y
 * no admite que se desenlacen.
 *
 * La tercera es la protección de la topología: un binding del sistema que desaparece no borra nada
 * visible, pero los mensajes que se publiquen por esa ruta se descartan sin que ningún servicio dé
 * error.
 */
@ExtendWith(MockitoExtension.class)
class BindingServiceTest {

    @Mock
    private AmqpAdmin amqpAdmin;
    @Mock
    private ManagementApiClient gestion;

    private BindingService servicio;

    @BeforeEach
    void preparar() {
        // La lista real de la topología: el exchange directo, el topic, el DLX y las colas que
        // aparecen en estos casos.
        RabbitMQProperties propiedades = new RabbitMQProperties(
                "andesstay",
                new RabbitMQProperties.Management("http://localhost:15672", "admin", "secreto"),
                List.of("q.cmd.email", "q.cmd.email.dlq", "cmd.direct", "cmd.topic", "cmd.dead.dlx"));
        servicio = new BindingService(amqpAdmin, gestion, propiedades);
    }

    @Test
    @DisplayName("el origen y el destino no se intercambian al construir el Binding")
    void origenYDestinoEnSuSitio() {
        servicio.crear(new CrearBindingRequest(
                "ex.demo", "q.demo.email", "QUEUE", "demo.send", null));

        ArgumentCaptor<Binding> declarado = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).declareBinding(declarado.capture());

        Binding binding = declarado.getValue();
        // Si estos dos se invirtieran, el binding iría de la cola al exchange: no entregaría nada
        // y el broker no se quejaría.
        assertEquals("ex.demo", binding.getExchange(), "el exchange es el ORIGEN");
        assertEquals("q.demo.email", binding.getDestination(), "la cola es el DESTINO");
        assertEquals("demo.send", binding.getRoutingKey());
        assertEquals(Binding.DestinationType.QUEUE, binding.getDestinationType());
    }

    @Test
    @DisplayName("el destino puede ser otro exchange, que es lo que permite encadenarlos")
    void destinoPuedeSerExchange() {
        servicio.crear(new CrearBindingRequest(
                "ex.demo", "ex.demo.copia", "EXCHANGE", "demo.#", null));

        ArgumentCaptor<Binding> declarado = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).declareBinding(declarado.capture());

        assertEquals(Binding.DestinationType.EXCHANGE, declarado.getValue().getDestinationType());
    }

    @Test
    @DisplayName("sin tipoDestino se asume QUEUE, que es el caso habitual")
    void tipoDestinoPorOmisionEsQueue() {
        servicio.crear(new CrearBindingRequest(
                "ex.demo", "q.demo.voucher", null, "demo.gen", null));

        ArgumentCaptor<Binding> declarado = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).declareBinding(declarado.capture());

        assertEquals(Binding.DestinationType.QUEUE, declarado.getValue().getDestinationType());
    }

    @Test
    @DisplayName("tipoDestino no distingue mayúsculas")
    void tipoDestinoSinMayusculas() {
        servicio.crear(new CrearBindingRequest(
                "ex.demo", "ex.demo.copia", "exchange", "demo.#", null));

        ArgumentCaptor<Binding> declarado = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).declareBinding(declarado.capture());

        assertEquals(Binding.DestinationType.EXCHANGE, declarado.getValue().getDestinationType());
    }

    @Test
    @DisplayName("una routing key ausente se normaliza a cadena vacía, no a null")
    void routingKeyAusenteEsCadenaVacia() {
        // Un fanout no usa routing key, así que omitirla es legítimo. Pasar null al broker sí
        // sería un error.
        servicio.crear(new CrearBindingRequest(
                "cmd.fanout", "q.pruebas.demo", "QUEUE", null, null));

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
                () -> servicio.eliminar(exchange, "q.demo.email", TipoDestino.QUEUE, ""));

        assertTrue(e.getMessage().contains("implícitos"),
                "el mensaje tiene que explicar por qué: " + e.getMessage());
        verify(amqpAdmin, never()).removeBinding(any());
    }

    @Test
    @DisplayName("eliminar un binding fuera de la topología sí llega al broker")
    void eliminarBindingNormal() {
        existe("ex.demo", "q.demo.email", "demo.send", null);

        servicio.eliminar("ex.demo", "q.demo.email", TipoDestino.QUEUE, "demo.send");

        ArgumentCaptor<Binding> quitado = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).removeBinding(quitado.capture());

        assertEquals("ex.demo", quitado.getValue().getExchange());
        assertEquals("q.demo.email", quitado.getValue().getDestination());
    }

    /** Simula que la API de gestión informa ese binding entre el exchange y la cola. */
    private void existe(String exchange, String cola, String routingKey, Map<String, Object> argumentos) {
        when(gestion.bindingsEntre(exchange, cola, true)).thenReturn(List.of(
                new ManagementApiClient.BindingApi(exchange, cola, "queue", routingKey, argumentos)));
    }

    // ── Existencia antes de la baja ──────────────────────────────────────────

    @Nested
    @DisplayName("baja de un binding que no existe")
    class Inexistente {

        @Test
        @DisplayName("sin ningún binding entre el exchange y la cola da 404 y no llega al broker")
        void sinBindingDa404() {
            // queue.unbind responde bien aunque la regla no exista: sin la comprobación, la API
            // respondería 204 a una baja equivocada.
            when(gestion.bindingsEntre("ex.demo", "q.demo.email", true)).thenReturn(List.of());

            RecursoNoEncontradoException e = assertThrows(RecursoNoEncontradoException.class,
                    () -> servicio.eliminar("ex.demo", "q.demo.email", TipoDestino.QUEUE, "demo.send"));

            assertEquals("binding", e.getRecurso());
            assertEquals("ex.demo -> q.demo.email (rk=demo.send)", e.getNombre());
            verify(amqpAdmin, never()).removeBinding(any());
        }

        @Test
        @DisplayName("un binding entre los mismos extremos con otra routing key no cuenta")
        void otraRoutingKeyDa404() {
            existe("ex.demo", "q.demo.email", "demo.otra", null);

            assertThrows(RecursoNoEncontradoException.class,
                    () -> servicio.eliminar("ex.demo", "q.demo.email", TipoDestino.QUEUE, "demo.send"));
            verify(amqpAdmin, never()).removeBinding(any());
        }

        @Test
        @DisplayName("hacia un exchange, la consulta pide el segmento de exchange y no el de cola")
        void destinoExchange() {
            when(gestion.bindingsEntre("ex.demo", "ex.demo.copia", false)).thenReturn(List.of());

            assertThrows(RecursoNoEncontradoException.class,
                    () -> servicio.eliminar("ex.demo", "ex.demo.copia", TipoDestino.EXCHANGE, "demo.#"));
            verify(gestion).bindingsEntre("ex.demo", "ex.demo.copia", false);
        }

        @Test
        @DisplayName("la baja usa los argumentos del binding existente, o el broker no lo reconoce")
        void usaLosArgumentosExistentes() {
            existe("ex.demo", "q.demo.email", "", Map.of("x-match", "all", "plantilla", "voucher"));

            servicio.eliminar("ex.demo", "q.demo.email", TipoDestino.QUEUE, "");

            ArgumentCaptor<Binding> quitado = ArgumentCaptor.forClass(Binding.class);
            verify(amqpAdmin).removeBinding(quitado.capture());
            assertEquals("all", quitado.getValue().getArguments().get("x-match"));
        }
    }

    // ── Protección de la topología ───────────────────────────────────────────

    @Nested
    @DisplayName("bindings de la topología de AndesStay")
    class Protegidos {

        @Test
        @DisplayName("no se crea un binding HACIA una cola protegida: metería mensajes ajenos en ella")
        void crearHaciaColaProtegida() {
            RecursoProtegidoException e = assertThrows(RecursoProtegidoException.class,
                    () -> servicio.crear(new CrearBindingRequest(
                            "ex.demo", "q.cmd.email", "QUEUE", "demo.#", null)));

            assertEquals("q.cmd.email", e.getNombre());
            assertTrue(e.getMessage().contains("hacia la cola 'q.cmd.email'"), e.getMessage());
            verify(amqpAdmin, never()).declareBinding(any());
        }

        @Test
        @DisplayName("el binding del sistema cmd.direct -> q.cmd.email no se puede quitar")
        void bindingDelSistema() {
            RecursoProtegidoException e = assertThrows(RecursoProtegidoException.class,
                    () -> servicio.eliminar("cmd.direct", "q.cmd.email", TipoDestino.QUEUE, "email.send"));

            assertTrue(e.getMessage().contains("el binding del sistema 'cmd.direct -> q.cmd.email'"), e.getMessage());
            verify(amqpAdmin, never()).removeBinding(any());
            // La protección se decide antes de consultar nada: no hace falta la API de gestión.
            verify(gestion, never()).bindingsEntre(anyString(), anyString(), anyBoolean());
        }

        @Test
        @DisplayName("tampoco el de un exchange del sistema hacia una DLQ del sistema")
        void bindingDelSistemaHaciaDlq() {
            assertThrows(RecursoProtegidoException.class,
                    () -> servicio.eliminar("cmd.dead.dlx", "q.cmd.email.dlq", TipoDestino.QUEUE, "email.dead"));
            verify(amqpAdmin, never()).removeBinding(any());
        }

        @Test
        @DisplayName("entre dos exchanges del sistema también cuenta como topología")
        void entreExchangesDelSistema() {
            assertThrows(RecursoProtegidoException.class,
                    () -> servicio.crear(new CrearBindingRequest(
                            "cmd.topic", "cmd.direct", "EXCHANGE", "email.#", null)));
            verify(amqpAdmin, never()).declareBinding(any());
        }

        @Test
        @DisplayName("no se elimina un binding que LLEGA a una cola protegida, aunque salga de otro exchange")
        void eliminarHaciaColaProtegida() {
            RecursoProtegidoException e = assertThrows(RecursoProtegidoException.class,
                    () -> servicio.eliminar("ex.demo", "q.cmd.email.dlq", TipoDestino.QUEUE, "demo.dead"));

            assertEquals("q.cmd.email.dlq", e.getNombre());
            verify(amqpAdmin, never()).removeBinding(any());
        }

        @Test
        @DisplayName("desde un exchange del sistema hacia una cola que no es del sistema: se crea y se elimina")
        void desdeExchangeProtegidoHaciaColaLibre() {
            // Solo agrega una copia de los mensajes: no le quita nada a la topología. Es lo que
            // permite, por ejemplo, observar el tráfico de cmd.topic en una cola de pruebas, y
            // retirarla después sin tener que borrar la cola.
            servicio.crear(new CrearBindingRequest(
                    "cmd.topic", "q.demo.espia", "QUEUE", "email.*", null));
            verify(amqpAdmin).declareBinding(any(Binding.class));

            existe("cmd.topic", "q.demo.espia", "email.*", null);
            servicio.eliminar("cmd.topic", "q.demo.espia", TipoDestino.QUEUE, "email.*");
            verify(amqpAdmin).removeBinding(any(Binding.class));
        }
    }
}
