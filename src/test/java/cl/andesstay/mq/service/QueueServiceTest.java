package cl.andesstay.mq.service;

import cl.andesstay.mq.broker.ManagementApiClient;
import cl.andesstay.mq.config.RabbitMQProperties;
import cl.andesstay.mq.dto.ColaResumen;
import cl.andesstay.mq.dto.CrearColaRequest;
import cl.andesstay.mq.dto.OperacionResultado;
import cl.andesstay.mq.web.RecursoNoEncontradoException;
import cl.andesstay.mq.web.RecursoProtegidoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lo que de verdad puede salir mal en el servicio de colas.
 *
 * No se prueba que AmqpAdmin hable bien con el broker —eso es de Spring AMQP y de RabbitMQ— sino
 * las decisiones propias: qué nombres se rechazan antes de molestar al broker, qué operaciones
 * exigen confirmación y cómo se traduce la respuesta de cada una.
 *
 * Las tres que importan más son el prefijo reservado, el seguro de los protegidos y la asimetría
 * entre eliminar y purgar: las tres son sitios donde un cambio inocente produce una respuesta
 * mentirosa en lugar de un fallo visible.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QueueServiceTest {

    private static final String COLA_PROTEGIDA = "q.cmd.email";
    private static final String COLA_LIBRE = "q.pruebas.demo";

    @Mock
    private AmqpAdmin amqpAdmin;
    @Mock
    private ManagementApiClient gestion;

    private QueueService servicio;

    @BeforeEach
    void preparar() {
        // Las propiedades son un record, así que se construyen en lugar de simularse: un mock de
        // un objeto de configuración solo añadiría ruido.
        RabbitMQProperties propiedades = new RabbitMQProperties(
                "andesstay",
                new RabbitMQProperties.Management("http://localhost:15672", "admin", "secreto"),
                List.of(COLA_PROTEGIDA, "cmd.direct"));

        servicio = new QueueService(amqpAdmin, gestion, propiedades);

        // declareQueue devuelve el nombre de la cola declarada.
        when(amqpAdmin.declareQueue(any(Queue.class)))
                .thenAnswer(i -> i.getArgument(0, Queue.class).getName());
        when(amqpAdmin.deleteQueue(anyString())).thenReturn(true);
    }

    private static CrearColaRequest peticion(String nombre) {
        return new CrearColaRequest(nombre, null, null, null, null);
    }

    // ── Alta ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("crear una cola la declara durable por omisión, no transitoria")
    void durablePorOmision() {
        servicio.crear(peticion(COLA_LIBRE));

        ArgumentCaptor<Queue> declarada = ArgumentCaptor.forClass(Queue.class);
        verify(amqpAdmin).declareQueue(declarada.capture());

        Queue cola = declarada.getValue();
        assertEquals(COLA_LIBRE, cola.getName());
        // Es el motivo de que los tres booleanos del DTO sean Boolean y no boolean: con un
        // primitivo, omitir el campo habría creado una cola que desaparece al reiniciar el broker.
        assertTrue(cola.isDurable(), "una cola omitida tiene que nacer durable");
        assertFalse(cola.isExclusive());
        assertFalse(cola.isAutoDelete());
    }

    @Test
    @DisplayName("los argumentos llegan al broker tal cual, sin filtrar")
    void argumentosSinFiltrar() {
        Map<String, Object> argumentos = Map.of(
                "x-message-ttl", 300000,
                "x-dead-letter-exchange", "cmd.dead.dlx");

        servicio.crear(new CrearColaRequest(COLA_LIBRE, true, false, false, argumentos));

        ArgumentCaptor<Queue> declarada = ArgumentCaptor.forClass(Queue.class);
        verify(amqpAdmin).declareQueue(declarada.capture());
        // Replicar aquí la tabla de argumentos de RabbitMQ sería una copia condenada a quedarse
        // atrás: valida el broker, y lo que esta API garantiza es no estorbar.
        assertEquals(argumentos, declarada.getValue().getArguments());
    }

    @Nested
    @DisplayName("el prefijo reservado por AMQP")
    class PrefijoReservado {

        /**
         * Es el caso que motiva la clase NombresAmqp. RabbitAdmin SALTA en silencio las
         * declaraciones cuyo nombre empieza por amq. y devuelve null, así que sin la validación la
         * API respondería 201 sobre una cola que nunca se creó.
         */
        @ParameterizedTest
        @ValueSource(strings = {"amq.direct", "amq.gen-abc123", "amq."})
        @DisplayName("se rechaza con 400 sin llegar a tocar el broker")
        void seRechazaAntesDelBroker(String nombre) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> servicio.crear(peticion(nombre)));

            assertTrue(e.getMessage().contains("amq."),
                    "el mensaje tiene que nombrar el prefijo: " + e.getMessage());
            verify(amqpAdmin, never()).declareQueue(any(Queue.class));
        }

        @Test
        @DisplayName("un nombre que solo CONTIENE amq. sí se acepta")
        void contenerNoEsEmpezar() {
            // La restricción del protocolo es sobre el prefijo. Rechazar por contener dejaría
            // fuera nombres legítimos.
            servicio.crear(peticion("q.cmd.amq.interno"));

            verify(amqpAdmin).declareQueue(any(Queue.class));
        }
    }

    @Test
    @DisplayName("si el broker no confirma la declaración, falla ruidosamente en lugar de responder 201")
    void sinConfirmacionDelBrokerFalla() {
        when(amqpAdmin.declareQueue(any(Queue.class))).thenReturn(null);

        // Un 500 con traza es mejor que un 201 sobre una cola inexistente: el segundo deja a quien
        // integra convencido de que la cola está.
        assertThrows(IllegalStateException.class, () -> servicio.crear(peticion(COLA_LIBRE)));
    }

    // ── Baja y purga, con el seguro de los protegidos ────────────────────────

    @Test
    @DisplayName("eliminar una cola de la topología de AndesStay sin forzar se rechaza")
    void protegidaNoSeEliminaSinForzar() {
        RecursoProtegidoException e = assertThrows(RecursoProtegidoException.class,
                () -> servicio.eliminar(COLA_PROTEGIDA, false));

        assertEquals(COLA_PROTEGIDA, e.getNombre());
        assertTrue(e.getMessage().contains("forzar=true"),
                "el mensaje tiene que decir cómo proceder: " + e.getMessage());
        // Lo esencial: no se llegó a borrar nada. Borrar una cola en RabbitMQ es irreversible.
        verify(amqpAdmin, never()).deleteQueue(anyString());
    }

    @Test
    @DisplayName("con forzar=true la cola protegida sí se elimina")
    void protegidaSeEliminaConForzar() {
        servicio.eliminar(COLA_PROTEGIDA, true);

        // El seguro protege del error de dedo, no de la intención: sigue siendo una API de
        // administración completa.
        verify(amqpAdmin).deleteQueue(COLA_PROTEGIDA);
    }

    @Test
    @DisplayName("purgar también pasa por el seguro: descartar notificaciones pendientes es destructivo")
    void purgarProtegidaTambienSeRechaza() {
        assertThrows(RecursoProtegidoException.class, () -> servicio.purgar(COLA_PROTEGIDA, false));

        verify(amqpAdmin, never()).purgeQueue(anyString());
    }

    @Test
    @DisplayName("una cola libre se elimina sin confirmación")
    void libreSeEliminaDirecto() {
        OperacionResultado resultado = servicio.eliminar(COLA_LIBRE, false);

        verify(amqpAdmin).deleteQueue(COLA_LIBRE);
        assertEquals("eliminada", resultado.operacion());
    }

    /**
     * La asimetría entre las dos operaciones, que es un detalle de Spring AMQP fácil de olvidar:
     * deleteQueue captura el NOT_FOUND del canal y devuelve false, mientras purgeQueue lo deja
     * subir como excepción. Por eso el 404 de eliminar sale de un booleano y el de purgar del
     * manejador de errores.
     */
    @Test
    @DisplayName("eliminar una cola inexistente da 404 a partir del booleano, no de una excepción del broker")
    void eliminarInexistenteDa404() {
        when(amqpAdmin.deleteQueue(COLA_LIBRE)).thenReturn(false);

        RecursoNoEncontradoException e = assertThrows(RecursoNoEncontradoException.class,
                () -> servicio.eliminar(COLA_LIBRE, false));

        assertEquals(COLA_LIBRE, e.getNombre());
        assertEquals("cola", e.getRecurso());
    }

    @Test
    @DisplayName("purgar devuelve cuántos mensajes se fueron")
    void purgarDevuelveElConteo() {
        when(amqpAdmin.purgeQueue(COLA_LIBRE)).thenReturn(17);

        OperacionResultado resultado = servicio.purgar(COLA_LIBRE, false);

        // El conteo es el motivo de responder 200 con cuerpo en lugar de un 204 vacío.
        assertEquals(17, resultado.mensajesPurgados());
        assertEquals("purgada", resultado.operacion());
    }

    // ── Consulta ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("describir una cola va por AMQP, no por la API de gestión")
    void describirNoUsaLaApiDeGestion() {
        when(amqpAdmin.getQueueInfo(COLA_LIBRE))
                .thenReturn(new QueueInformation(COLA_LIBRE, 5, 1));

        ColaResumen resumen = servicio.describir(COLA_LIBRE);

        assertEquals(5, resumen.mensajes());
        assertEquals(1, resumen.consumidores());
        // Es la única lectura del servicio que sobrevive a un 15672 inalcanzable, y de eso depende
        // poder diagnosticar justamente cuando la API de gestión no responde.
        verify(gestion, never()).colas();
    }

    @Test
    @DisplayName("describir una cola inexistente da 404")
    void describirInexistenteDa404() {
        when(amqpAdmin.getQueueInfo(COLA_LIBRE)).thenReturn(null);

        assertThrows(RecursoNoEncontradoException.class, () -> servicio.describir(COLA_LIBRE));
    }

    @Test
    @DisplayName("el listado marca las colas protegidas y ordena por nombre")
    void listadoMarcaYOrdena() {
        when(gestion.colas()).thenReturn(List.of(
                new ManagementApiClient.ColaApi(COLA_LIBRE, 0, 0, true, false, false,
                        null, "running", "rabbit@rabbitmq1", null),
                new ManagementApiClient.ColaApi(COLA_PROTEGIDA, 3, 1, true, false, false,
                        Map.of("x-message-ttl", 300000), "running", "rabbit@rabbitmq1",
                        "ha-colas-cmd")));

        List<ColaResumen> colas = servicio.listar();

        assertEquals(COLA_PROTEGIDA, colas.get(0).nombre(), "tiene que venir ordenado por nombre");
        assertTrue(colas.get(0).protegida());
        assertFalse(colas.get(1).protegida());
        // El campo politica es lo que delata el espejado: la política ha-colas-cmd se aplica por el
        // patrón del NOMBRE, no por ningún argumento de la cola.
        assertEquals("ha-colas-cmd", colas.get(0).politica());
        // Los argumentos ausentes se normalizan a mapa vacío, no se dejan en null.
        assertEquals(Map.of(), colas.get(1).argumentos());
    }
}
