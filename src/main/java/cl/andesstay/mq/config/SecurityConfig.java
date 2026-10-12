package cl.andesstay.mq.config;

import cl.andesstay.mq.security.AzureRolesConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Validación del JWT de Entra ID y exigencia del rol Admin.
 *
 * Este servicio sí lleva starter de security, al contrario que ms-andesstay-notify: ahí la
 * superficie HTTP es un endpoint de estado de solo lectura que no se publica, y aquí son
 * operaciones que borran colas. Un DELETE sobre q.cmd.email se lleva por delante las
 * notificaciones del sistema, así que la autorización no es opcional.
 *
 * El rol se exige con @PreAuthorize en los controladores y no con un pathMatcher por ruta: las
 * tres familias de recursos piden exactamente lo mismo, y tenerlo junto al método evita que una
 * ruta nueva nazca sin protección por haberse olvidado de la cadena.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Sin estado y sin formularios: es una API con Bearer token, no hay sesión que
            // proteger de un CSRF.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Solo health. El healthcheck del contenedor lo consulta sin token, y es lo único
                // que queda abierto: /actuator/metrics describiría el broker a cualquiera.
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            );
        return http.build();
    }

    /**
     * Traduce el claim "roles" del token a authorities con prefijo ROLE_, que es lo que
     * hasRole('Admin') espera encontrar.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter convertidor = new JwtAuthenticationConverter();
        convertidor.setJwtGrantedAuthoritiesConverter(new AzureRolesConverter());
        return convertidor;
    }
}
