package cl.andesstay.mq.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Convierte el claim "roles" de Azure AD en authorities ROLE_* y deriva el rol Cliente para los
 * huéspedes autoregistrados.
 *
 * Es la misma regla que aplican ms-andesstay-bff y ms-andesstay-reservations: el servicio no
 * confía en que el BFF ya decidió el rol. Entra ID no asigna App Roles a quien se registra con el
 * user flow de invitados, por lo que su token llega sin claim "roles". Se considera huésped al
 * token delegado de un invitado: claim opcional "acct" = 1 y scope AndesStay.Access en "scp". Si
 * el token ya trae roles, se respetan.
 *
 * En este servicio la derivación de Cliente no habilita nada: todas las rutas exigen el rol Admin.
 * Se conserva igual que en los otros dos repos para que la clase siga siendo la misma en los tres
 * y una corrección de la regla no haya que hacerla tres veces de formas distintas.
 */
public class AzureRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    public static final String ROLE_PREFIX = "ROLE_";
    public static final String GUEST_ROLE = "Cliente";
    public static final String API_SCOPE = "AndesStay.Access";

    /** Valor del claim "acct" para cuentas invitadas; 0 identifica a los miembros del tenant. */
    private static final int GUEST_ACCOUNT = 1;

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles != null) {
            roles.forEach(role -> authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + role)));
        }
        if (authorities.isEmpty() && isGuestWithApiScope(jwt)) {
            authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + GUEST_ROLE));
        }
        return authorities;
    }

    /** Indica si el token es de un invitado y fue emitido en su nombre para esta API. */
    static boolean isGuestWithApiScope(Jwt jwt) {
        Object acct = jwt.getClaim("acct");
        boolean guest = acct instanceof Number number
                ? number.intValue() == GUEST_ACCOUNT
                : acct != null && String.valueOf(GUEST_ACCOUNT).equals(acct.toString());
        if (!guest) {
            return false;
        }
        String scp = jwt.getClaimAsString("scp");
        return scp != null && Arrays.asList(scp.split(" ")).contains(API_SCOPE);
    }
}
