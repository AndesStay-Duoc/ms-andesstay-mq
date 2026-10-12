package cl.andesstay.mq.controller;

import cl.andesstay.mq.config.SecurityConfig;
import cl.andesstay.mq.dto.OperacionResultado;
import cl.andesstay.mq.service.QueueService;
import cl.andesstay.mq.web.ManejadorDeErrores;
import cl.andesstay.mq.web.RecursoNoEncontradoException;
import cl.andesstay.mq.web.RecursoProtegidoException;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpIOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La matriz de códigos HTTP del recurso de colas, de punta a punta de la capa web.
 *
 * Es la evidencia de autorización y de manejo de errores: cada aserción de aquí corresponde a una
 * fila de la batería de Postman, y se ejecuta sin broker, sin tokens reales y sin tener que
 * levantar nada. Lo que NO cubre es la conversación con RabbitMQ; eso es de QueueServiceTest y de
 * la validación manual contra el broker.
 *
 * El JwtDecoder se simula porque si no el contexto intentaría descargar las claves públicas de
 * Entra ID al arrancar, y los tests dejarían de funcionar sin red.
 */
@WebMvcTest(QueueController.class)
@Import({SecurityConfig.class, ManejadorDeErrores.class})
class QueueControllerTest {

    private static final String RUTA = "/api/v1/mq/queues";
    private static final String CUERPO_VALIDO = "{\"nombre\":\"q.pruebas.demo\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QueueService servicio;

    @MockBean
    private JwtDecoder jwtDecoder;

    /** Token con el rol que la API exige. */
    private static MockHttpServletRequestBuilder comoAdmin(MockHttpServletRequestBuilder peticion) {
        return peticion.with(jwt().authorities(new org.springframework.security.core.authority
                .SimpleGrantedAuthority("ROLE_Admin")));
    }

    /** Token válido pero del rol de huésped: autenticado y sin permiso. */
    private static MockHttpServletRequestBuilder comoCliente(MockHttpServletRequestBuilder peticion) {
        return peticion.with(jwt().authorities(new org.springframework.security.core.authority
                .SimpleGrantedAuthority("ROLE_Cliente")));
    }

    // ── 401 · sin token ──────────────────────────────────────────────────────

    @Test
    @DisplayName("401 · sin token no se llega ni al controlador")
    void sinTokenEs401() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(CUERPO_VALIDO))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(RUTA + "/q.pruebas.demo")).andExpect(status().isUnauthorized());

        // Lo esencial: la autorización corta ANTES del servicio. Un 401 que hubiera llegado a
        // tocar el broker ya sería un problema.
        verify(servicio, never()).listar();
        verify(servicio, never()).crear(any());
        verify(servicio, never()).eliminar(anyString(), anyBoolean());
    }

    // ── 403 · token válido, rol insuficiente ─────────────────────────────────

    @Test
    @DisplayName("403 · un token de Cliente está autenticado pero no puede administrar colas")
    void clienteEs403() throws Exception {
        mockMvc.perform(comoCliente(get(RUTA)))
                .andExpect(status().isForbidden());
        mockMvc.perform(comoCliente(delete(RUTA + "/q.cmd.email")))
                .andExpect(status().isForbidden());

        verify(servicio, never()).eliminar(anyString(), anyBoolean());
    }

    // ── 2xx · la ruta feliz ──────────────────────────────────────────────────

    @Test
    @DisplayName("201 · un Admin declara la cola")
    void adminCrea201() throws Exception {
        when(servicio.crear(any())).thenReturn(
                OperacionResultado.de("cola", "q.pruebas.demo", "declarada", "declarada"));

        mockMvc.perform(comoAdmin(post(RUTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_VALIDO)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nombre").value("q.pruebas.demo"))
                .andExpect(jsonPath("$.operacion").value("declarada"));
    }

    @Test
    @DisplayName("200 · un Admin lista las colas")
    void adminLista200() throws Exception {
        when(servicio.listar()).thenReturn(java.util.List.of());

        mockMvc.perform(comoAdmin(get(RUTA))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("204 · un Admin elimina una cola libre, sin cuerpo en la respuesta")
    void adminElimina204() throws Exception {
        when(servicio.eliminar(eq("q.pruebas.demo"), eq(false))).thenReturn(
                OperacionResultado.de("cola", "q.pruebas.demo", "eliminada", "eliminada"));

        mockMvc.perform(comoAdmin(delete(RUTA + "/q.pruebas.demo")))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("200 · la purga responde con el conteo, por eso no es 204")
    void purga200ConConteo() throws Exception {
        when(servicio.purgar(eq("q.cmd.email.dlq"), eq(true)))
                .thenReturn(OperacionResultado.purga("q.cmd.email.dlq", 42));

        mockMvc.perform(comoAdmin(post(RUTA + "/q.cmd.email.dlq/purge").param("forzar", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mensajesPurgados").value(42));
    }

    // ── 400 · peticiones mal formadas ────────────────────────────────────────

    @Test
    @DisplayName("400 · sin nombre, con el campo que falla señalado")
    void sinNombreEs400() throws Exception {
        mockMvc.perform(comoAdmin(post(RUTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDACION"))
                .andExpect(jsonPath("$.campos.nombre").exists());

        verify(servicio, never()).crear(any());
    }

    @Test
    @DisplayName("400 · el prefijo amq. se rechaza antes de tocar el broker")
    void prefijoReservadoEs400() throws Exception {
        // Sin esto, RabbitAdmin saltaría la declaración en silencio y la API respondería 201 sobre
        // una cola que nunca se creó. Ver NombresAmqp.
        when(servicio.crear(any())).thenThrow(
                new IllegalArgumentException("el prefijo amq. está reservado por AMQP"));

        mockMvc.perform(comoAdmin(post(RUTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"amq.direct\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PETICION_INVALIDA"));
    }

    @Test
    @DisplayName("400 · JSON mal formado no sale como 500")
    void jsonRotoEs400() throws Exception {
        mockMvc.perform(comoAdmin(post(RUTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CUERPO_ILEGIBLE"));
    }

    // ── 404 y 409 · los dos casos normales de una API de administración ──────

    @Test
    @DisplayName("404 · eliminar una cola inexistente")
    void inexistenteEs404() throws Exception {
        when(servicio.eliminar(anyString(), anyBoolean()))
                .thenThrow(new RecursoNoEncontradoException("cola", "q.no.existe"));

        mockMvc.perform(comoAdmin(delete(RUTA + "/q.no.existe")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RECURSO_NO_ENCONTRADO"))
                .andExpect(jsonPath("$.nombre").value("q.no.existe"));
    }

    @Test
    @DisplayName("409 · eliminar una cola de la topología de AndesStay sin forzar")
    void protegidaEs409() throws Exception {
        when(servicio.eliminar(eq("q.cmd.email"), eq(false)))
                .thenThrow(new RecursoProtegidoException("cola", "q.cmd.email", "eliminar"));

        mockMvc.perform(comoAdmin(delete(RUTA + "/q.cmd.email")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RECURSO_PROTEGIDO"))
                // El 409 tiene que decir cómo proceder, o hay que leer el código fuente.
                .andExpect(jsonPath("$.detalle").value(
                        org.hamcrest.Matchers.containsString("forzar=true")));
    }

    @Test
    @DisplayName("409 · redeclarar con argumentos distintos, con el texto del broker incluido")
    void argumentosIncompatiblesEs409() throws Exception {
        String delBroker = "PRECONDITION_FAILED - inequivalent arg x-message-ttl for queue q.cmd.email";
        AMQP.Channel.Close cierre = new AMQP.Channel.Close.Builder()
                .replyCode(406).replyText(delBroker).classId(50).methodId(10).build();
        when(servicio.crear(any())).thenThrow(new AmqpIOException(
                new IOException(new ShutdownSignalException(false, false, cierre, null))));

        mockMvc.perform(comoAdmin(post(RUTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"q.cmd.email\",\"argumentos\":{\"x-message-ttl\":1}}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TOPOLOGIA_INCOMPATIBLE"))
                // El texto del broker nombra el argumento concreto que difiere.
                .andExpect(jsonPath("$.detalleDelBroker").value(delBroker));
    }

    // ── 503 · el broker o su API de gestión no están ─────────────────────────

    @Test
    @DisplayName("503 · sin API de gestión el listado falla, porque AMQP no sabe listar")
    void sinApiDeGestionEs503() throws Exception {
        when(servicio.listar()).thenThrow(new cl.andesstay.mq.web.ApiDeGestionException(
                "sin conexión", false, null));

        mockMvc.perform(comoAdmin(get(RUTA)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("GESTION_NO_DISPONIBLE"));
    }

    @Test
    @DisplayName("503 · broker caído")
    void brokerCaidoEs503() throws Exception {
        doThrow(new org.springframework.amqp.AmqpConnectException(new IOException("refused")))
                .when(servicio).crear(any());

        mockMvc.perform(comoAdmin(post(RUTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO_VALIDO)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("BROKER_NO_DISPONIBLE"));
    }

    // ── El health check, que es lo único abierto ─────────────────────────────

    @Test
    @DisplayName("el resto de Actuator no queda abierto junto al health")
    void actuatorNoQuedaAbierto() throws Exception {
        // /actuator/health es permitAll porque lo consulta el healthcheck del contenedor sin token.
        // Que esa excepción no se haya llevado por delante al resto conviene tenerlo afirmado:
        // /actuator/metrics describiría el broker a cualquiera que alcance el puerto.
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }
}
