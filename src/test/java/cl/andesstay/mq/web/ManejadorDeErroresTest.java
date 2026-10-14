package cl.andesstay.mq.web;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La traducción de los errores del broker a códigos HTTP.
 *
 * Es la lógica más frágil del servicio y la que no se puede comprobar leyendo el código: el motivo
 * por el que el broker rechazó una operación no viaja en el mensaje de la excepción, sino en el
 * `reason` de una ShutdownSignalException enterrada DOS niveles dentro de una AmqpIOException. Si
 * ese recorrido se rompe, los dos casos más normales de una API de administración —redeclarar con
 * argumentos distintos y operar sobre algo inexistente— vuelven a salir como 500, y nada más falla.
 *
 * Por eso aquí se construyen las cadenas de excepciones a mano, con la misma forma que las arma
 * Spring AMQP.
 */
class ManejadorDeErroresTest {

    private final ManejadorDeErrores manejador = new ManejadorDeErrores();

    /**
     * Reproduce la forma exacta en que llega un error de canal: la señal de cierre del cliente de
     * RabbitMQ, envuelta en una IOException, envuelta en una AmqpIOException.
     */
    private static AmqpIOException errorDeCanal(int codigo, String texto) {
        AMQP.Channel.Close cierre = new AMQP.Channel.Close.Builder()
                .replyCode(codigo)
                .replyText(texto)
                .classId(50)      // queue
                .methodId(10)     // declare
                .build();
        ShutdownSignalException senal = new ShutdownSignalException(false, false, cierre, null);
        return new AmqpIOException(new IOException(senal));
    }

    // ── El recorrido de la cadena de causas ──────────────────────────────────

    @Test
    @DisplayName("encuentra el cierre dos niveles dentro de la cadena")
    void encuentraElCierreAnidado() {
        ManejadorDeErrores.CierreAmqp cierre =
                ManejadorDeErrores.cierreAmqp(errorDeCanal(406, "PRECONDITION_FAILED - x"));

        assertNotNull(cierre, "el recorrido de causas no encontró la señal de cierre");
        assertEquals(406, cierre.codigo());
        assertEquals("PRECONDITION_FAILED - x", cierre.texto());
    }

    @Test
    @DisplayName("devuelve null cuando el fallo no viene del broker")
    void sinSenalDevuelveNull() {
        // Un fallo del lado del cliente no lleva señal de cierre. Tiene que distinguirse, porque la
        // respuesta correcta ahí no es un 409 ni un 404.
        assertNull(ManejadorDeErrores.cierreAmqp(new AmqpException("se interrumpió el hilo")));
    }

    @Test
    @DisplayName("una cadena de causas cíclica no cuelga el bucle")
    void cadenaCiclicaNoCuelga() {
        // No debería ocurrir nunca, pero el precio de comprobarlo es una línea y el precio de no
        // hacerlo es un hilo de Tomcat girando para siempre.
        AmqpException ciclica = new AmqpException("vuelta") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertNull(ManejadorDeErrores.cierreAmqp(ciclica));
    }

    // ── Los dos casos que motivan la clase ───────────────────────────────────

    @Test
    @DisplayName("406 PRECONDITION_FAILED se traduce a 409 y conserva el texto del broker")
    void precondicionFallidaDa409() {
        String delBroker = "PRECONDITION_FAILED - inequivalent arg x-message-ttl for queue q.cmd.email";

        ResponseEntity<Map<String, Object>> respuesta =
                manejador.errorDelBroker(errorDeCanal(406, delBroker));

        assertEquals(HttpStatus.CONFLICT, respuesta.getStatusCode());
        assertEquals("TOPOLOGIA_INCOMPATIBLE", cuerpo(respuesta).get("error"));
        // El texto del broker nombra el argumento concreto que difiere, que es más útil para quien
        // integra que cualquier mensaje propio. Por eso se devuelve tal cual.
        assertEquals(delBroker, cuerpo(respuesta).get("detalleDelBroker"));
    }

    @Test
    @DisplayName("404 NOT_FOUND del broker se traduce a 404, no a 500")
    void noEncontradoDa404() {
        ResponseEntity<Map<String, Object>> respuesta = manejador.errorDelBroker(
                errorDeCanal(404, "NOT_FOUND - no queue q.inexistente in vhost andesstay"));

        assertEquals(HttpStatus.NOT_FOUND, respuesta.getStatusCode());
        assertEquals("RECURSO_NO_ENCONTRADO", cuerpo(respuesta).get("error"));
    }

    // ── El resto de la clasificación ─────────────────────────────────────────

    @Test
    @DisplayName("405 RESOURCE_LOCKED se traduce a 409")
    void recursoBloqueadoDa409() {
        ResponseEntity<Map<String, Object>> respuesta = manejador.errorDelBroker(
                errorDeCanal(405, "RESOURCE_LOCKED - cannot obtain exclusive access"));

        assertEquals(HttpStatus.CONFLICT, respuesta.getStatusCode());
        assertEquals("RECURSO_BLOQUEADO", cuerpo(respuesta).get("error"));
    }

    @Test
    @DisplayName("403 ACCESS_REFUSED se traduce a 502 y no a 403")
    void accesoRechazadoDa502() {
        ResponseEntity<Map<String, Object>> respuesta = manejador.errorDelBroker(
                errorDeCanal(403, "ACCESS_REFUSED - access to queue denied"));

        // Un 403 aquí sería engañoso: no le falta permiso a quien llama, le falta a las
        // credenciales de ESTE servicio. Es un defecto de configuración, no de la petición.
        assertEquals(HttpStatus.BAD_GATEWAY, respuesta.getStatusCode());
        assertEquals("BROKER_RECHAZO_ACCESO", cuerpo(respuesta).get("error"));
    }

    @Test
    @DisplayName("un código desconocido no se pierde: va en la respuesta")
    void codigoDesconocidoSeInforma() {
        ResponseEntity<Map<String, Object>> respuesta =
                manejador.errorDelBroker(errorDeCanal(530, "algo nuevo"));

        assertEquals(HttpStatus.BAD_GATEWAY, respuesta.getStatusCode());
        assertEquals(530, cuerpo(respuesta).get("codigoAmqp"));
    }

    @Test
    @DisplayName("un fallo AMQP sin cierre de canal da 502, sin inventar un código")
    void sinCierreDa502() {
        ResponseEntity<Map<String, Object>> respuesta =
                manejador.errorDelBroker(new AmqpException("fallo del cliente"));

        assertEquals(HttpStatus.BAD_GATEWAY, respuesta.getStatusCode());
        assertEquals("ERROR_DEL_BROKER", cuerpo(respuesta).get("error"));
    }

    @Test
    @DisplayName("broker inalcanzable da 503, que es lo que un cliente debe reintentar")
    void brokerCaidoDa503() {
        for (AmqpException e : new AmqpException[]{
                new AmqpConnectException(new IOException("connection refused")),
                new AmqpTimeoutException("se agotó la espera")}) {

            ResponseEntity<Map<String, Object>> respuesta = manejador.brokerCaido(e);

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, respuesta.getStatusCode(),
                    e.getClass().getSimpleName() + " tiene que dar 503");
            assertEquals("BROKER_NO_DISPONIBLE", cuerpo(respuesta).get("error"));
        }
    }

    // ── La API de gestión, que es la otra vía ────────────────────────────────

    @Test
    @DisplayName("la API de gestión inalcanzable da 503 y explica que el listado la necesita")
    void gestionInalcanzableDa503() {
        ResponseEntity<Map<String, Object>> respuesta = manejador.apiDeGestion(
                new ApiDeGestionException("sin conexión", false, null));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, respuesta.getStatusCode());
        assertEquals("GESTION_NO_DISPONIBLE", cuerpo(respuesta).get("error"));
        // El mensaje nombra el 15672 y el motivo: AMQP no tiene operación de listado. Sin eso, el
        // 503 de un GET parece una caída del servicio y no un puerto que falta abrir.
        assertTrue(String.valueOf(cuerpo(respuesta).get("detalle")).contains("15672"));
    }

    @Test
    @DisplayName("la API de gestión que responde con error da 502, no 503")
    void gestionQueRechazaDa502() {
        ResponseEntity<Map<String, Object>> respuesta = manejador.apiDeGestion(
                new ApiDeGestionException("la API de gestión respondió 401", true, null));

        // Contestó, así que está levantada: el problema son las credenciales o el vhost, y
        // reintentar no lo arregla. De ahí 502 y no 503.
        assertEquals(HttpStatus.BAD_GATEWAY, respuesta.getStatusCode());
        assertEquals("GESTION_RECHAZO", cuerpo(respuesta).get("error"));
        assertTrue(String.valueOf(cuerpo(respuesta).get("detalle")).contains("administrator"));
    }

    // ── Errores de negocio ───────────────────────────────────────────────────

    @Test
    @DisplayName("el seguro de los protegidos da 409 y dice cómo proceder")
    void protegidoDa409ConLaSalida() {
        ResponseEntity<Map<String, Object>> respuesta = manejador.protegido(
                new RecursoProtegidoException("cola", "q.cmd.email", "eliminar"));

        assertEquals(HttpStatus.CONFLICT, respuesta.getStatusCode());
        assertEquals("RECURSO_PROTEGIDO", cuerpo(respuesta).get("error"));
        // Un 409 que no dice qué hacer a continuación obliga a leer el código fuente.
        assertTrue(String.valueOf(cuerpo(respuesta).get("detalle")).contains("forzar=true"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cuerpo(ResponseEntity<Map<String, Object>> respuesta) {
        return (Map<String, Object>) respuesta.getBody();
    }
}
