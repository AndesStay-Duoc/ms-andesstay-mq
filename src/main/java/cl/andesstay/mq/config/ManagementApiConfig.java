package cl.andesstay.mq.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Cliente de la API HTTP de gestion de RabbitMQ, en el puerto 15672.
 *
 * POR QUE HACE FALTA. El protocolo AMQP tiene `queue.declare` y `queue.delete`, asi que crear y
 * borrar se resuelve entero con AmqpAdmin. Lo que AMQP NO tiene es ninguna operacion de listado:
 * no existe un `queue.list`. La unica forma de enumerar las colas, los exchanges o los bindings de
 * un vhost es la API de gestion, y de ahi este segundo cliente.
 *
 * Por eso el servicio habla con el broker por dos vias, que es una asimetria que conviene tener
 * clara: las escrituras van por AMQP (5672) y las lecturas de inventario por HTTP (15672).
 */
@Configuration
public class ManagementApiConfig {

    /** La API de gestion responde en milisegundos; si no lo hace, es que no esta. */
    private static final int TIMEOUT_CONEXION_MS = 3_000;
    private static final int TIMEOUT_LECTURA_MS = 5_000;

    /**
     * Los timeouts son el motivo de construir la fabrica a mano.
     *
     * Sin ellos, con el puerto 15672 filtrado —que es el caso normal: el compose lo publica solo en
     * loopback— un GET de listado se quedaria colgado hasta el timeout del sistema operativo y
     * retendria un hilo de Tomcat. Con tres segundos, la peticion falla rapido y el
     * @RestControllerAdvice la traduce a un 503.
     */
    @Bean
    public RestClient managementRestClient(RabbitMQProperties propiedades) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(TIMEOUT_CONEXION_MS);
        fabrica.setReadTimeout(TIMEOUT_LECTURA_MS);

        RabbitMQProperties.Management mgmt = propiedades.management();
        return RestClient.builder()
                .baseUrl(mgmt.url())
                .requestFactory(fabrica)
                // La API de gestion usa autenticacion basica. Se fija aqui, una vez, en lugar de en
                // cada llamada: el cliente que reciben los servicios ya viene autenticado.
                .defaultHeaders(h -> h.setBasicAuth(mgmt.usuario(),
                        mgmt.password() == null ? "" : mgmt.password()))
                .build();
    }
}
