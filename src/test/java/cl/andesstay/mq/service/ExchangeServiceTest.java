package cl.andesstay.mq.service;

import cl.andesstay.mq.broker.ManagementApiClient;
import cl.andesstay.mq.config.RabbitMQProperties;
import cl.andesstay.mq.dto.ExchangeResumen;
import cl.andesstay.mq.web.RecursoNoEncontradoException;
import cl.andesstay.mq.web.RecursoProtegidoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** La consulta de un exchange y la protección de los de la topología al eliminarlos. */
@ExtendWith(MockitoExtension.class)
class ExchangeServiceTest {

    @Mock
    private AmqpAdmin amqpAdmin;
    @Mock
    private ManagementApiClient gestion;

    private ExchangeService servicio;

    @BeforeEach
    void preparar() {
        RabbitMQProperties propiedades = new RabbitMQProperties(
                "andesstay",
                new RabbitMQProperties.Management("http://localhost:15672", "admin", "secreto"),
                List.of("cmd.direct", "cmd.dead.dlx"));
        servicio = new ExchangeService(amqpAdmin, gestion, propiedades);
    }

    @Test
    @DisplayName("describir traduce la respuesta de la API de gestión y marca si está protegido")
    void describirExistente() {
        when(gestion.exchange("cmd.dead.dlx")).thenReturn(Optional.of(new ManagementApiClient.ExchangeApi(
                "cmd.dead.dlx", "direct", true, false, false, null)));

        ExchangeResumen resumen = servicio.describir("cmd.dead.dlx");

        assertEquals("cmd.dead.dlx", resumen.nombre());
        assertEquals("direct", resumen.tipo());
        assertTrue(resumen.durable());
        // Sin argumentos el broker manda null; la API responde un mapa vacío, igual que el listado.
        assertEquals(Map.of(), resumen.argumentos());
        assertTrue(resumen.protegido());
    }

    @Test
    @DisplayName("un exchange fuera de la topología sale como no protegido")
    void describirLibre() {
        when(gestion.exchange("ex.demo")).thenReturn(Optional.of(new ManagementApiClient.ExchangeApi(
                "ex.demo", "topic", true, false, false, Map.of())));

        assertFalse(servicio.describir("ex.demo").protegido());
    }

    @Test
    @DisplayName("describir uno inexistente es 404, no un resumen vacío")
    void describirInexistente() {
        when(gestion.exchange("ex.no.existe")).thenReturn(Optional.empty());

        RecursoNoEncontradoException e = assertThrows(RecursoNoEncontradoException.class,
                () -> servicio.describir("ex.no.existe"));

        assertEquals("ex.no.existe", e.getNombre());
    }

    @ParameterizedTest(name = "forzar={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("un exchange de la topología no se elimina, ni siquiera con forzar=true")
    void protegidoNoSeElimina(boolean forzar) {
        RecursoProtegidoException e = assertThrows(RecursoProtegidoException.class,
                () -> servicio.eliminar("cmd.direct", forzar));

        assertEquals("cmd.direct", e.getNombre());
        verify(amqpAdmin, never()).deleteExchange(anyString());
    }

    @Test
    @DisplayName("un exchange libre se elimina, con o sin forzar")
    void libreSeElimina() {
        when(gestion.exchange("ex.demo")).thenReturn(Optional.of(new ManagementApiClient.ExchangeApi(
                "ex.demo", "topic", true, false, false, Map.of())));
        when(amqpAdmin.deleteExchange("ex.demo")).thenReturn(true);

        servicio.eliminar("ex.demo", false);
        servicio.eliminar("ex.demo", true);

        verify(amqpAdmin, org.mockito.Mockito.times(2)).deleteExchange("ex.demo");
    }

    @Test
    @DisplayName("eliminar un exchange inexistente da 404 y no llega a pedir la baja al broker")
    void eliminarInexistenteDa404() {
        // Con RabbitMQ 3.13, deleteExchange devolvería true aunque no exista: exchange.delete es
        // idempotente. Por eso la existencia se comprueba antes, contra la API de gestión.
        when(gestion.exchange("ex.no.existe")).thenReturn(Optional.empty());

        RecursoNoEncontradoException e = assertThrows(RecursoNoEncontradoException.class,
                () -> servicio.eliminar("ex.no.existe", false));

        assertEquals("ex.no.existe", e.getNombre());
        verify(amqpAdmin, never()).deleteExchange(anyString());
    }
}
