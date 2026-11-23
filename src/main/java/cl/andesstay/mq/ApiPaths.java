package cl.andesstay.mq;

/**
 * Prefijos de las rutas HTTP, en un solo sitio.
 *
 * Se usan constantes y no @Value porque @RequestMapping exige una constante en tiempo de
 * compilacion. La concatenacion de dos `static final String` literales lo es; el valor de un
 * @Value se resuelve en ejecucion y no compilaria.
 *
 * Alternativa descartada: server.servlet.context-path o spring.mvc.servlet.path. Las dos remapean
 * el DispatcherServlet completo, y Actuator se sirve a traves de el, asi que /actuator/health
 * pasaria a /api/v1/actuator/health y rompria el healthcheck del contenedor. Con una constante,
 * Actuator queda fuera del prefijo por construccion.
 */
public final class ApiPaths {

    public static final String V1 = "/api/v1";
    public static final String MQ = V1 + "/mq";

    public static final String QUEUES = MQ + "/queues";
    public static final String EXCHANGES = MQ + "/exchanges";
    public static final String BINDINGS = MQ + "/bindings";

    private ApiPaths() {
    }
}
