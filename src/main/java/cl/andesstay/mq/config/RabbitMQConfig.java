package cl.andesstay.mq.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans de mensajeria. Aqui no hay ni una regla de negocio, y es deliberado: esta clase solo
 * construye el cliente del broker.
 *
 * IMPORTANTE: este servicio NO declara ninguna Queue, Exchange ni Binding como @Bean. RabbitAdmin
 * declara automaticamente al arrancar todo Declarable que encuentre en el contexto, asi que un
 * @Bean de cola aqui competiria con la declaracion que hace ms-andesstay-notify y, en cuanto los
 * argumentos difirieran en algo, el broker responderia PRECONDITION_FAILED y cerraria el canal.
 * La topologia de AndesStay tiene un unico dueno, y es notify. Este servicio declara solo lo que
 * le piden por HTTP.
 */
@Slf4j
@Configuration
public class RabbitMQConfig {

    /**
     * Se declara explicitamente en lugar de confiar en la autoconfiguracion por dos motivos.
     *
     * Uno: `setAutoStartup(false)` evita que RabbitAdmin intente declarar nada en el arranque. Sin
     * esto, un servicio de administracion que arranca con el broker a medio levantar deja un error
     * en el log por algo que no iba a declarar de todas formas.
     *
     * Dos: `setIgnoreDeclarationExceptions(false)` es lo que hace que un fallo del broker LLEGUE al
     * llamador. Con true, un PRECONDITION_FAILED se registraria en el log y la API devolveria 201
     * sobre una cola que nunca se creo, que es la peor respuesta posible para una API de
     * administracion. Es el valor por omision, pero aqui se escribe porque es una decision.
     */
    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(false);
        admin.setIgnoreDeclarationExceptions(false);
        return admin;
    }
}
