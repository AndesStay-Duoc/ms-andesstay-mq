package cl.andesstay.mq.dto;

/**
 * Formatos que la API acepta en nombres, routing keys y tipos de destino.
 *
 * RabbitMQ admite en un nombre casi cualquier carácter hasta 255 bytes, pero un nombre con espacios,
 * barras o tildes complica las URL de esta API y de la consola, y casi siempre es un error de
 * tipeo. Se restringe al conjunto que ya usa la topología de AndesStay (q.cmd.email.dlq,
 * cmd.dead.dlx). El mismo criterio se probó antes en ms-andesstay-rabbitadmin.
 *
 * Los nombres «amq.*» pasan este formato a propósito: no son inválidos sino reservados, y los
 * rechaza NombresAmqp con un mensaje que explica el motivo.
 */
public final class Restricciones {

    private Restricciones() {
    }

    /** Límite de AMQP para nombres y routing keys: viajan en un short string de 255 bytes. */
    public static final int LARGO_MAXIMO = 255;

    /**
     * '*' y no '+': el vacío ya lo informa @NotBlank, y así el mismo error no sale dos veces. En
     * los parámetros de la baja de un binding permite además el exchange vacío, que BindingService
     * rechaza con un mensaje propio.
     */
    public static final String CARACTERES_DE_NOMBRE = "[A-Za-z0-9._:-]*";
    public static final String MENSAJE_CARACTERES_DE_NOMBRE =
            "solo admite letras sin tilde, dígitos y los caracteres . _ - :";

    /** Una routing key admite los comodines * y # de los exchanges topic, pero no espacios. */
    public static final String ROUTING_KEY = "\\S*";
    public static final String MENSAJE_ROUTING_KEY = "no puede contener espacios";

    /** Los dos valores de TipoDestino, sin distinguir mayúsculas. */
    public static final String TIPOS_DESTINO = "(?i)QUEUE|EXCHANGE";
    public static final String MENSAJE_TIPOS_DESTINO = "debe ser QUEUE o EXCHANGE";

    public static final String MENSAJE_LARGO = "no puede pasar de " + LARGO_MAXIMO + " caracteres";
}
