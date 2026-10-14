package cl.andesstay.mq.web;

/**
 * 409 · Se intentó destruir un recurso de la topología de AndesStay sin confirmar.
 *
 * Borrar q.cmd.email deja a ms-andesstay-notify sin su cola de trabajo y tira las notificaciones
 * del sistema, y en RabbitMQ un borrado de cola es irreversible: se van los mensajes que tuviera
 * dentro. El seguro no prohíbe la operación, exige decirlo a propósito con ?forzar=true.
 */
public class RecursoProtegidoException extends RuntimeException {

    private final String nombre;

    public RecursoProtegidoException(String recurso, String nombre, String operacion) {
        super(operacion + " '" + nombre + "' afectaría a la topología de AndesStay. "
                + "Si es intencionado, repite la petición con ?forzar=true");
        this.nombre = nombre;
    }

    public String getNombre() {
        return nombre;
    }
}
