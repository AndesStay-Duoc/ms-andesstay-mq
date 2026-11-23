package cl.andesstay.mq.controller;

import cl.andesstay.mq.ApiPaths;
import cl.andesstay.mq.dto.ColaResumen;
import cl.andesstay.mq.dto.CrearColaRequest;
import cl.andesstay.mq.dto.OperacionResultado;
import cl.andesstay.mq.service.QueueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Colas.
 *
 * El prefijo sale de la constante ApiPaths.QUEUES, que es el único @RequestMapping del recurso: los
 * demás mappings son relativos a él, así que una futura v2 se declara en un solo sitio.
 *
 * El rol Admin se exige a nivel de clase. Al estar junto al recurso y no en la cadena de filtros,
 * un método nuevo no puede nacer sin protección por haberse olvidado de añadir su pathMatcher.
 */
@RestController
@RequestMapping(ApiPaths.QUEUES)
@PreAuthorize("hasRole('Admin')")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService servicio;

    /**
     * 201 · Declara la cola.
     *
     * Se responde 201 y no 200 aunque declarar sea idempotente: para quien llama el resultado es
     * que la cola existe con la definición pedida, y el campo detalle de la respuesta aclara lo
     * que pasó. Una redeclaración con argumentos distintos no llega aquí: sale como 409.
     */
    @PostMapping
    public ResponseEntity<OperacionResultado> crear(@Valid @RequestBody CrearColaRequest peticion) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.crear(peticion));
    }

    /** 200 · Lista las colas del vhost. Necesita la API de gestión: AMQP no sabe listar. */
    @GetMapping
    public List<ColaResumen> listar() {
        return servicio.listar();
    }

    /**
     * 200 · Describe una sola cola.
     *
     * Va por AMQP, así que responde incluso cuando la API de gestión del 15672 no es alcanzable.
     */
    @GetMapping("/{nombre}")
    public ColaResumen describir(@PathVariable String nombre) {
        return servicio.describir(nombre);
    }

    /**
     * 204 · Elimina la cola, con los mensajes que contenga.
     *
     * Los nombres de la topología de AndesStay responden 409: borrar q.cmd.email deja a
     * ms-andesstay-notify sin su cola de trabajo, y en RabbitMQ es irreversible.
     *
     * Ya no hay parámetro forzar: no levantaba la protección y solo cambiaba el log (ver
     * RecursoProtegidoException). Quien aún lo envíe recibe la misma respuesta, porque Spring
     * ignora los parámetros de consulta que el método no declara.
     */
    @DeleteMapping("/{nombre}")
    public ResponseEntity<Void> eliminar(@PathVariable String nombre) {
        servicio.eliminar(nombre);
        return ResponseEntity.noContent().build();
    }

    /**
     * 200 · Vacía la cola sin eliminarla, y devuelve cuántos mensajes se fueron.
     *
     * Es un POST sobre un subrecurso y no un DELETE porque no borra la cola: la deja existiendo y
     * vacía. El conteo es el motivo de responder 200 con cuerpo en lugar de 204.
     */
    @PostMapping("/{nombre}/purge")
    public OperacionResultado purgar(@PathVariable String nombre) {
        return servicio.purgar(nombre);
    }
}
