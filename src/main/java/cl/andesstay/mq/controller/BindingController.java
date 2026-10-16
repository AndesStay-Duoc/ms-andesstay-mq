package cl.andesstay.mq.controller;

import cl.andesstay.mq.ApiPaths;
import cl.andesstay.mq.dto.BindingResumen;
import cl.andesstay.mq.dto.CrearBindingRequest;
import cl.andesstay.mq.dto.OperacionResultado;
import cl.andesstay.mq.dto.Restricciones;
import cl.andesstay.mq.dto.TipoDestino;
import cl.andesstay.mq.service.BindingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Bindings: las reglas que conectan un exchange con una cola o con otro exchange.
 *
 * Un binding no tiene nombre propio —lo identifica la tripleta (exchange, destino, routing key)—
 * así que la baja no puede llevar un identificador en la ruta y lleva los tres valores como
 * parámetros de consulta. Es la única de las tres familias de recursos que se desvía del patrón
 * /recurso/{nombre}, y se desvía porque el modelo de AMQP no da otra opción.
 */
@RestController
@RequestMapping(ApiPaths.BINDINGS)
@PreAuthorize("hasRole('Admin')")
@RequiredArgsConstructor
public class BindingController {

    private final BindingService servicio;

    /** 201 · Declara el binding. Repetir la misma tripleta es idempotente. */
    @PostMapping
    public ResponseEntity<OperacionResultado> crear(
            @Valid @RequestBody CrearBindingRequest peticion) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.crear(peticion));
    }

    /**
     * 200 · Lista los bindings del vhost, incluidos los implícitos del exchange por omisión.
     *
     * Esos aparecen con el origen vacío y no se pueden eliminar, pero se dejan a la vista porque
     * son los que explican que publicar con el nombre de una cola como routing key llegue a esa
     * cola, que es el mecanismo con el que ms-andesstay-notify encola sus reintentos.
     */
    @GetMapping
    public List<BindingResumen> listar() {
        return servicio.listar();
    }

    /**
     * 204 · Elimina el binding identificado por la tripleta.
     *
     * No lleva forzar: un binding borrado se restituye con una sola petición y no se pierde ningún
     * mensaje ya encolado. Lo que sí se descartan son los que se publiquen mientras no existe, y la
     * respuesta lo advierte. Los bindings de la topología de AndesStay —los que salen de un exchange
     * protegido o llegan a un recurso protegido— responden 409.
     *
     * Los parámetros se validan con el mismo formato que el cuerpo del alta. tipoDestino llega como
     * texto por la misma razón que allí: un valor desconocido sale como error de validación y no
     * como un fallo de conversión con otra forma de respuesta.
     */
    @DeleteMapping
    public ResponseEntity<Void> eliminar(
            @RequestParam
            @Size(max = Restricciones.LARGO_MAXIMO, message = Restricciones.MENSAJE_LARGO)
            @Pattern(regexp = Restricciones.CARACTERES_DE_NOMBRE,
                    message = Restricciones.MENSAJE_CARACTERES_DE_NOMBRE)
            String exchange,
            @RequestParam
            @Size(max = Restricciones.LARGO_MAXIMO, message = Restricciones.MENSAJE_LARGO)
            @Pattern(regexp = Restricciones.CARACTERES_DE_NOMBRE,
                    message = Restricciones.MENSAJE_CARACTERES_DE_NOMBRE)
            String destino,
            @RequestParam(defaultValue = "QUEUE")
            @Pattern(regexp = Restricciones.TIPOS_DESTINO, message = Restricciones.MENSAJE_TIPOS_DESTINO)
            String tipoDestino,
            @RequestParam(defaultValue = "")
            @Size(max = Restricciones.LARGO_MAXIMO, message = Restricciones.MENSAJE_LARGO)
            @Pattern(regexp = Restricciones.ROUTING_KEY, message = Restricciones.MENSAJE_ROUTING_KEY)
            String routingKey) {
        servicio.eliminar(exchange, destino,
                TipoDestino.valueOf(tipoDestino.toUpperCase(Locale.ROOT)), routingKey);
        return ResponseEntity.noContent().build();
    }
}
