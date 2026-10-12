package cl.andesstay.mq.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Configuracion de mensajeria de este servicio, tipada y en un solo sitio.
 *
 * Esta clase existe para que la configuracion no se reparta en @Value sueltos por los servicios:
 * la logica de negocio recibe un objeto validado y no sabe de donde salieron los valores.
 *
 * La separacion importa por algo concreto. Con @Value repartidos, cambiar el nombre de una
 * propiedad obliga a buscarla por todo el codigo y un error solo aparece al arrancar; aqui el
 * contrato esta en un sitio, se valida al construirlo y los servicios no pueden leer una
 * propiedad que no exista.
 *
 * La conexion AMQP (host, puerto, usuario, contrasena, vhost) la sigue leyendo Spring Boot de
 * spring.rabbitmq.*: no se duplica aqui. Lo que vive en este prefijo es lo que Boot no conoce.
 */
@Validated
@ConfigurationProperties("andesstay.mq")
public record RabbitMQProperties(

        /**
         * Virtual host sobre el que opera la API. Se necesita por separado de
         * spring.rabbitmq.virtual-host porque la API de gestion lo lleva en la URL, no en la
         * conexion.
         */
        @NotBlank String vhost,

        /** Datos de la API HTTP de gestion, que es lo unico que permite LISTAR (ver ManagementApiConfig). */
        @Valid Management management,

        /**
         * Nombres que esta API se niega a eliminar o purgar sin confirmacion explicita.
         *
         * Son las colas y los exchanges de la topologia de AndesStay. Un DELETE accidental sobre
         * q.cmd.email deja a ms-andesstay-notify sin su cola de trabajo y tira las notificaciones
         * del sistema entero, y un borrado de cola en RabbitMQ es irreversible: se van los mensajes
         * que tuviera dentro.
         *
         * No es una prohibicion, es un seguro: con ?forzar=true la operacion procede. Asi la API
         * sigue siendo una API de administracion completa y el error de dedo deja de ser posible.
         */
        List<String> protegidos
) {

    /**
     * @param url      base de la API de gestion, sin barra final. Ej: http://rabbitmq1:15672
     * @param usuario  normalmente el mismo que el de AMQP; necesita la etiqueta "administrator"
     *                 en el broker para leer /api
     */
    public record Management(@NotBlank String url, @NotBlank String usuario, String password) {
    }

    /** Indica si el nombre esta en la lista de protegidos. */
    public boolean esProtegido(String nombre) {
        return protegidos != null && protegidos.contains(nombre);
    }
}
