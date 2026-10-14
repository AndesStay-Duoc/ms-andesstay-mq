# ms-andesstay-mq

Microservicio de **administración de la mensajería** del sistema AndesStay. Expone una API REST
para crear, consultar y eliminar las colas, los exchanges y los bindings del clúster de RabbitMQ.

Es un servicio de administración, no de dominio: no consume ni publica mensajes de negocio. Lo que
hace es dar al operador una vía auditable y autorizada para operar la topología sin entrar por la
Management UI ni por `rabbitmqctl`.

## Las dos vías hacia el broker

El servicio habla con RabbitMQ por **dos puertos distintos**, y la asimetría es deliberada:

| Operación | Vía | Puerto |
|---|---|---|
| Crear, eliminar, purgar | AMQP, con `AmqpAdmin` | 5672 |
| **Listar** | API HTTP de gestión | 15672 |

El motivo es que el protocolo AMQP 0-9-1 define `queue.declare`, `queue.delete` y
`exchange.declare`, pero **no define ninguna operación de listado**: no existe un `queue.list`.
Enumerar lo que hay en un vhost solo se puede hacer contra la API de gestión. Eso tiene una
consecuencia práctica al desplegar: si el 15672 no es alcanzable, las altas y las bajas siguen
funcionando y los listados devuelven `503`.

La única lectura que no depende de la API de gestión es `GET /api/v1/mq/queues/{nombre}`, que va
por AMQP. Existe precisamente para poder diagnosticar cuando la API de gestión no responde.

## Rutas

Todas exigen un JWT de Entra ID con el **rol `Admin`**.

| Verbo | Ruta | Códigos |
|---|---|---|
| `POST` | `/api/v1/mq/queues` | `201` `400` `401` `403` `409` `503` |
| `GET` | `/api/v1/mq/queues` | `200` `401` `403` `503` |
| `GET` | `/api/v1/mq/queues/{nombre}` | `200` `401` `403` `404` |
| `DELETE` | `/api/v1/mq/queues/{nombre}` | `204` `401` `403` `404` `409` |
| `POST` | `/api/v1/mq/queues/{nombre}/purge` | `200` `401` `403` `404` `409` |
| `POST` | `/api/v1/mq/exchanges` | `201` `400` `401` `403` `409` |
| `GET` | `/api/v1/mq/exchanges` | `200` `401` `403` `503` |
| `DELETE` | `/api/v1/mq/exchanges/{nombre}` | `204` `401` `403` `404` `409` |
| `POST` | `/api/v1/mq/bindings` | `201` `400` `401` `403` `404` |
| `GET` | `/api/v1/mq/bindings` | `200` `401` `403` `503` |
| `DELETE` | `/api/v1/mq/bindings` | `204` `400` `401` `403` `404` |

Un binding no tiene nombre propio —lo identifica la tripleta `(exchange, destino, routing key)`—
así que su baja lleva los tres valores como parámetros de consulta en lugar de un identificador en
la ruta. Es la única desviación del patrón `/recurso/{nombre}`, y la impone el modelo de AMQP.

## El seguro sobre la topología de AndesStay

Las nueve colas y los tres exchanges del caso están en una lista de **protegidos**
(`andesstay.mq.protegidos`). Eliminarlos o purgarlos responde `409` y explica cómo proceder:

```
DELETE /api/v1/mq/queues/q.cmd.email?forzar=true
```

No es una prohibición, es un seguro contra el error de dedo. Borrar `q.cmd.email` deja a
`ms-andesstay-notify` sin su cola de trabajo y tira las notificaciones del sistema entero, y en
RabbitMQ borrar una cola es **irreversible**: se van los mensajes que tuviera dentro.

La purga pasa por el mismo seguro, porque descartar notificaciones pendientes es igual de
destructivo para lo que importa. El caso legítimo habitual —vaciar una DLQ ya analizada— solo
necesita añadir `?forzar=true`.

## Lo que este servicio NO hace

**No declara la topología de AndesStay.** Las tres colas de trabajo, sus DLQ, sus colas de
reintento y sus nueve bindings los declara `ms-andesstay-notify` con `@Bean`, que es quien consume.
Dos servicios declarando las mismas colas es una carrera que acaba en `PRECONDITION_FAILED` en
cuanto un argumento difiera, y cierra el canal de los dos. Aquí solo se declara lo que llega por
HTTP.

De ahí también que `RabbitAdmin` se construya con `setAutoStartup(false)`: no hay nada que declarar
al arrancar.

## Detalles que cuestan una tarde si no se conocen

**El prefijo `amq.` está reservado.** `RabbitAdmin` **salta en silencio** toda declaración cuyo
nombre empiece por `amq.`, deja una línea en el log a nivel `DEBUG` y no declara nada. Sin
validarlo antes, la API respondería `201` sobre una cola que nunca se creó. Lo rechaza
`service/NombresAmqp.java` con un `400`.

**El espejado lo decide el nombre, no los argumentos.** El broker tiene aplicada la política
`ha-colas-cmd` con el patrón `^q\.cmd\.`, así que toda cola creada por esta API cuyo nombre empiece
por `q.cmd.` queda espejada en los dos nodos automáticamente. No aparece en ningún argumento de la
petición; se comprueba en el campo `politica` de la respuesta del listado.

**`deleteQueue` y `purgeQueue` no fallan igual.** La primera captura el `NOT_FOUND` del canal y
devuelve `false`; la segunda lo deja subir como excepción. Por eso el `404` de eliminar sale de un
booleano y el de purgar del `@RestControllerAdvice`.

## Stack

Java 17 · Spring Boot 3.2.5 · Spring AMQP · Spring Security (OAuth2 Resource Server) · Actuator.

## Variables de entorno

| Variable | Descripción |
|---|---|
| `RABBITMQ_HOST` | Host del clúster RabbitMQ |
| `RABBITMQ_PORT` | Puerto AMQP, por omisión `5672` |
| `RABBITMQ_USER` | Usuario; necesita la etiqueta `administrator` para leer la API de gestión |
| `RABBITMQ_PASS` | Contraseña |
| `RABBITMQ_VHOST` | Virtual host, por omisión `andesstay` |
| `RABBITMQ_MGMT_URL` | Base de la API de gestión, por omisión `http://localhost:15672` |
| `AZURE_TENANT_ID` | Tenant de Entra ID |
| `AZURE_CLIENT_ID` | Registro de aplicación que emite el token |

Se configuran en un archivo `.env` que **no se versiona**. Ver `.env.example`.

La lista de protegidos **no es una variable de entorno** a propósito: desactivarla por
configuración sería perder justo lo que aporta. Para saltarse el seguro está `?forzar=true`.

## Cómo levantarlo

```bash
./mvnw spring-boot:run
```

Requiere el clúster de RabbitMQ en marcha. El compose está en el repositorio
[`infra`](https://github.com/AndesStay-Duoc/infra):

```bash
docker compose --env-file .env -f infra/mq/compose.yml up -d rabbitmq1 rabbitmq2 rabbitmq-init
```

## Pruebas

```bash
./mvnw test
```

Cubren la matriz completa de códigos HTTP del recurso de colas sin broker ni tokens reales, la
traducción de los códigos de cierre de canal de AMQP y las dos trampas del recurso de bindings.

## Contratos

Los exchanges, bindings y esquemas de mensaje son canónicos y viven en
[`infra/docs/contracts/events/rabbit.md`](https://github.com/AndesStay-Duoc/infra/blob/develop/docs/contracts/events/rabbit.md).

## Cómo contribuir

Ver [`CONTRIBUTING.md`](CONTRIBUTING.md).
