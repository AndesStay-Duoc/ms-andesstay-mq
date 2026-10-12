package cl.andesstay.mq.web;

/** 404 · La cola, el exchange o el binding que se pidió no existe en el vhost. */
public class RecursoNoEncontradoException extends RuntimeException {

    private final String recurso;
    private final String nombre;

    public RecursoNoEncontradoException(String recurso, String nombre) {
        super("no existe la " + recurso + " '" + nombre + "'");
        this.recurso = recurso;
        this.nombre = nombre;
    }

    public String getRecurso() {
        return recurso;
    }

    public String getNombre() {
        return nombre;
    }
}
