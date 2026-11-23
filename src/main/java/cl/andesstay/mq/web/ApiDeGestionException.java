package cl.andesstay.mq.web;

/**
 * La API HTTP de gestión del broker no respondió como se esperaba.
 *
 * Envuelve las excepciones del cliente HTTP para que los servicios no dependan de tipos de
 * org.springframework.web.client: la lógica de negocio solo tiene que saber que el inventario no
 * se pudo leer, no con qué cliente se intentó.
 *
 * @param alcanzable false cuando ni siquiera hubo conexión, que es el caso del puerto 15672
 *                   cerrado. Lo usa el manejador de errores para distinguir un 503 de un 502.
 */
public class ApiDeGestionException extends RuntimeException {

    private final boolean alcanzable;

    public ApiDeGestionException(String mensaje, boolean alcanzable, Throwable causa) {
        super(mensaje, causa);
        this.alcanzable = alcanzable;
    }

    public boolean esAlcanzable() {
        return alcanzable;
    }
}
