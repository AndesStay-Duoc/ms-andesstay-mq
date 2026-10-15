package cl.andesstay.mq.controller;

import cl.andesstay.mq.config.SecurityConfig;
import cl.andesstay.mq.dto.OperacionResultado;
import cl.andesstay.mq.dto.TipoDestino;
import cl.andesstay.mq.service.BindingService;
import cl.andesstay.mq.web.ManejadorDeErrores;
import cl.andesstay.mq.web.RecursoProtegidoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Validación de formato del recurso de bindings, en el cuerpo del alta y en los parámetros de la
 * baja, y el 409 de los bindings de la topología.
 *
 * Los dos caminos tienen que responder igual: 400 VALIDACION con el campo que falla. Spring los
 * valida por mecanismos distintos —@Valid en el cuerpo, validación de método en los parámetros— y
 * sin un manejador para el segundo saldría con otra forma de respuesta.
 */
@WebMvcTest(BindingController.class)
@Import({SecurityConfig.class, ManejadorDeErrores.class})
class BindingControllerTest {

    private static final String RUTA = "/api/v1/mq/bindings";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BindingService servicio;

    @MockBean
    private JwtDecoder jwtDecoder;

    private static MockHttpServletRequestBuilder comoAdmin(MockHttpServletRequestBuilder peticion) {
        return peticion.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Admin")));
    }

    private MockHttpServletRequestBuilder alta(String cuerpo) {
        return comoAdmin(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    // ── Alta ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("201 · un binding válido, con tipoDestino en minúsculas")
    void alta201() throws Exception {
        when(servicio.crear(any())).thenReturn(
                OperacionResultado.de("binding", "ex.demo -> q.demo (rk=demo.#)", "declarado", "declarado"));

        mockMvc.perform(alta("{\"exchange\":\"ex.demo\",\"destino\":\"q.demo\",\"tipoDestino\":\"queue\",\"routingKey\":\"demo.#\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("400 · un tipoDestino desconocido es un error del campo, no un cuerpo ilegible")
    void tipoDestinoDesconocido() throws Exception {
        mockMvc.perform(alta("{\"exchange\":\"ex.demo\",\"destino\":\"q.demo\",\"tipoDestino\":\"COLA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDACION"))
                .andExpect(jsonPath("$.campos.tipoDestino").value("debe ser QUEUE o EXCHANGE"));

        verify(servicio, never()).crear(any());
    }

    @Test
    @DisplayName("400 · nombres con caracteres no admitidos y routing key con espacios, todos a la vez")
    void variosCamposInvalidos() throws Exception {
        mockMvc.perform(alta("{\"exchange\":\"ex demo\",\"destino\":\"q/demo\",\"routingKey\":\"demo #\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDACION"))
                .andExpect(jsonPath("$.campos.exchange").value(containsString("solo admite")))
                .andExpect(jsonPath("$.campos.destino").value(containsString("solo admite")))
                .andExpect(jsonPath("$.campos.routingKey").value("no puede contener espacios"));

        verify(servicio, never()).crear(any());
    }

    @Test
    @DisplayName("409 · un binding hacia una cola protegida")
    void altaHaciaProtegida409() throws Exception {
        when(servicio.crear(any())).thenThrow(new RecursoProtegidoException(
                "q.cmd.email", "crear un binding hacia la cola 'q.cmd.email'"));

        mockMvc.perform(alta("{\"exchange\":\"ex.demo\",\"destino\":\"q.cmd.email\",\"routingKey\":\"demo.#\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RECURSO_PROTEGIDO"))
                .andExpect(jsonPath("$.nombre").value("q.cmd.email"));
    }

    // ── Baja ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("204 · la baja traduce tipoDestino al enum sin distinguir mayúsculas")
    void baja204() throws Exception {
        mockMvc.perform(comoAdmin(delete(RUTA)
                        .param("exchange", "ex.demo")
                        .param("destino", "ex.demo.copia")
                        .param("tipoDestino", "exchange")
                        .param("routingKey", "demo.#")))
                .andExpect(status().isNoContent());

        verify(servicio).eliminar("ex.demo", "ex.demo.copia", TipoDestino.EXCHANGE, "demo.#");
    }

    @Test
    @DisplayName("400 · los parámetros de la baja se validan con la misma forma de respuesta que el cuerpo")
    void bajaConParametrosInvalidos() throws Exception {
        mockMvc.perform(comoAdmin(delete(RUTA)
                        .param("exchange", "ex.demo")
                        .param("destino", "q demo")
                        .param("tipoDestino", "COLA")
                        .param("routingKey", "demo #")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDACION"))
                .andExpect(jsonPath("$.campos.destino").value(containsString("solo admite")))
                .andExpect(jsonPath("$.campos.tipoDestino").value("debe ser QUEUE o EXCHANGE"))
                .andExpect(jsonPath("$.campos.routingKey").value("no puede contener espacios"));

        verify(servicio, never()).eliminar(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("400 · el exchange vacío sigue llegando al servicio, que explica por qué no se puede")
    void bajaDelExchangePorOmision() throws Exception {
        doThrow(new IllegalArgumentException("el exchange por omisión tiene bindings implícitos"))
                .when(servicio).eliminar(eq(""), anyString(), any(), anyString());

        mockMvc.perform(comoAdmin(delete(RUTA).param("exchange", "").param("destino", "q.demo")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PETICION_INVALIDA"));
    }

    @Test
    @DisplayName("409 · quitar un binding de la topología")
    void bajaProtegida409() throws Exception {
        doThrow(new RecursoProtegidoException("cmd.direct",
                "eliminar un binding desde el exchange 'cmd.direct'"))
                .when(servicio).eliminar(eq("cmd.direct"), anyString(), any(), anyString());

        mockMvc.perform(comoAdmin(delete(RUTA)
                        .param("exchange", "cmd.direct")
                        .param("destino", "q.cmd.email")
                        .param("routingKey", "email.send")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RECURSO_PROTEGIDO"));
    }
}
