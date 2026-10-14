package cl.andesstay.mq.controller;

import cl.andesstay.mq.ApiPaths;
import cl.andesstay.mq.dto.CrearExchangeRequest;
import cl.andesstay.mq.dto.ExchangeResumen;
import cl.andesstay.mq.dto.OperacionResultado;
import cl.andesstay.mq.service.ExchangeService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Exchanges: las cuatro familias de AMQP, direct, topic, fanout y headers. */
@RestController
@RequestMapping(ApiPaths.EXCHANGES)
@PreAuthorize("hasRole('Admin')")
@RequiredArgsConstructor
public class ExchangeController {

    private final ExchangeService servicio;

    /** 201 · Declara el exchange. Cambiar el tipo de uno que ya existe sale como 409. */
    @PostMapping
    public ResponseEntity<OperacionResultado> crear(
            @Valid @RequestBody CrearExchangeRequest peticion) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicio.crear(peticion));
    }

    /** 200 · Lista los exchanges del vhost. */
    @GetMapping
    public List<ExchangeResumen> listar() {
        return servicio.listar();
    }

    /**
     * 204 · Elimina el exchange y sus bindings. Las colas enlazadas siguen existiendo.
     *
     * forzar es obligatorio para los nombres de la topología de AndesStay: sin cmd.direct, las
     * publicaciones de ms-andesstay-reservations dejan de llegar a ninguna cola.
     */
    @DeleteMapping("/{nombre}")
    public ResponseEntity<Void> eliminar(
            @PathVariable String nombre,
            @RequestParam(defaultValue = "false") boolean forzar) {
        servicio.eliminar(nombre, forzar);
        return ResponseEntity.noContent().build();
    }
}
