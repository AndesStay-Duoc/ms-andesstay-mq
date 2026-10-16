package cl.andesstay.mq.controller;

import cl.andesstay.mq.config.SecurityConfig;
import cl.andesstay.mq.dto.ExchangeResumen;
import cl.andesstay.mq.service.ExchangeService;
import cl.andesstay.mq.web.ManejadorDeErrores;
import cl.andesstay.mq.web.RecursoNoEncontradoException;
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

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La consulta de un exchange por nombre, la validación del alta y el 409 de los protegidos. */
@WebMvcTest(ExchangeController.class)
@Import({SecurityConfig.class, ManejadorDeErrores.class})
class ExchangeControllerTest {

    private static final String RUTA = "/api/v1/mq/exchanges";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ExchangeService servicio;

    @MockBean
    private JwtDecoder jwtDecoder;

    private static MockHttpServletRequestBuilder comoAdmin(MockHttpServletRequestBuilder peticion) {
        return peticion.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Admin")));
    }

    private static MockHttpServletRequestBuilder comoCliente(MockHttpServletRequestBuilder peticion) {
        return peticion.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Cliente")));
    }

    // ── GET /{nombre} ────────────────────────────────────────────────────────

    @Test
    @DisplayName("200 · describe un exchange por su nombre")
    void describe200() throws Exception {
        when(servicio.describir("cmd.dead.dlx")).thenReturn(new ExchangeResumen(
                "cmd.dead.dlx", "direct", true, false, false, Map.of(), true));

        mockMvc.perform(comoAdmin(get(RUTA + "/cmd.dead.dlx")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("cmd.dead.dlx"))
                .andExpect(jsonPath("$.tipo").value("direct"))
                .andExpect(jsonPath("$.protegido").value(true));
    }

    @Test
    @DisplayName("404 · un exchange inexistente, con el formato de error de la API")
    void describe404() throws Exception {
        when(servicio.describir("ex.no.existe"))
                .thenThrow(new RecursoNoEncontradoException("exchange", "ex.no.existe"));

        mockMvc.perform(comoAdmin(get(RUTA + "/ex.no.existe")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RECURSO_NO_ENCONTRADO"))
                .andExpect(jsonPath("$.nombre").value("ex.no.existe"));
    }

    @Test
    @DisplayName("401 y 403 · la consulta exige el rol Admin como el resto del recurso")
    void describeExigeAdmin() throws Exception {
        mockMvc.perform(get(RUTA + "/cmd.direct")).andExpect(status().isUnauthorized());
        mockMvc.perform(comoCliente(get(RUTA + "/cmd.direct"))).andExpect(status().isForbidden());

        verify(servicio, never()).describir(anyString());
    }

    // ── Validación del alta ──────────────────────────────────────────────────

    @Test
    @DisplayName("400 · un nombre con espacios o tildes no llega al servicio")
    void nombreConCaracteresInvalidos() throws Exception {
        mockMvc.perform(comoAdmin(post(RUTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"ex demo/1\",\"tipo\":\"TOPIC\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDACION"))
                .andExpect(jsonPath("$.campos.nombre").value(containsString("solo admite")));

        verify(servicio, never()).crear(any());
    }

    @Test
    @DisplayName("404 · eliminar un exchange que no existe")
    void eliminarInexistente404() throws Exception {
        when(servicio.eliminar(eq("ex.no.existe"), eq(false)))
                .thenThrow(new RecursoNoEncontradoException("exchange", "ex.no.existe"));

        mockMvc.perform(comoAdmin(delete(RUTA + "/ex.no.existe")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RECURSO_NO_ENCONTRADO"));
    }

    // ── 409 · protegidos ─────────────────────────────────────────────────────

    @Test
    @DisplayName("409 · eliminar un exchange de la topología, también con forzar=true")
    void protegidoEs409ConForzar() throws Exception {
        when(servicio.eliminar(eq("cmd.direct"), eq(true)))
                .thenThrow(new RecursoProtegidoException("el exchange", "cmd.direct", "eliminar"));

        mockMvc.perform(comoAdmin(delete(RUTA + "/cmd.direct").param("forzar", "true")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RECURSO_PROTEGIDO"))
                .andExpect(jsonPath("$.nombre").value("cmd.direct"));
    }
}
