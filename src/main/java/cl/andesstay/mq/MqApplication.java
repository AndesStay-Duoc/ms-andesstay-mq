package cl.andesstay.mq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * ms-andesstay-mq. API REST de administracion de la mensajeria: crea y elimina colas, exchanges y
 * bindings sobre el cluster de RabbitMQ.
 *
 * Es un servicio de administracion, no de dominio: no consume ni publica mensajes de negocio, y no
 * declara la topologia de AndesStay. Esa la declara ms-andesstay-notify con @Bean, que es quien
 * consume; dos servicios declarando las mismas colas es una carrera que acaba en
 * PRECONDITION_FAILED. Aqui solo se declara lo que llega por HTTP.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MqApplication {

    public static void main(String[] args) {
        SpringApplication.run(MqApplication.class, args);
    }
}
