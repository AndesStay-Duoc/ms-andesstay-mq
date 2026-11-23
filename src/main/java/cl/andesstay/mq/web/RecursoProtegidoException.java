package cl.andesstay.mq.web;

/**
 * 409 · Se intentó destruir o alterar un recurso de la topología de AndesStay.
 *
 * Borrar q.cmd.email deja a ms-andesstay-notify sin su cola de trabajo y tira las notificaciones
 * del sistema, y en RabbitMQ un borrado de cola es irreversible: se van los mensajes que tuviera
 * dentro. Quitar el binding cmd.direct -> q.cmd.email corta el flujo de correos sin borrar nada
 * visible.
 *
 * La protección no se puede saltar desde la API. Cambiarla exige cambiar la lista
 * andesstay.mq.protegidos y volver a desplegar, que es justamente el punto: un seguro que se
 * desactiva con un parámetro de la misma petición protege del error de dedo, pero no de un script
 * equivocado ni de quien no conoce la topología.
 *
 * Por eso ya no existe el parámetro ?forzar=true. Se aceptaba en los DELETE y en la purga, pero no
 * levantaba nada: solo cambiaba una palabra del log. Un parámetro que parece un interruptor y no lo
 * es invita a probarlo, y el mensaje de este 409 lo nombraba.
 */
public class RecursoProtegidoException extends RuntimeException {

    private final String nombre;

    /** Operación sobre el propio recurso protegido: eliminar o purgar una cola, eliminar un exchange. */
    public RecursoProtegidoException(String recurso, String nombre, String operacion) {
        this(nombre, operacion + " " + recurso + " '" + nombre + "'");
    }

    /**
     * @param nombre    el recurso protegido que motiva el rechazo
     * @param operacion lo que se intentó, ya redactado; por ejemplo «eliminar un binding desde el
     *                  exchange 'cmd.direct'»
     */
    public RecursoProtegidoException(String nombre, String operacion) {
        super("No se permite " + operacion + ": forma parte de la topología de AndesStay "
                + "(andesstay.mq.protegidos). Esta API no la modifica; cambiarla exige editar esa "
                + "lista y volver a desplegar");
        this.nombre = nombre;
    }

    public String getNombre() {
        return nombre;
    }
}
