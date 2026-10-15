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
| `GET` | `/api/v1/mq/exchanges/{nombre}` | `200` `401` `403` `404` `503` |
| `DELETE` | `/api/v1/mq/exchanges/{nombre}` | `204` `401` `403` `404` `409` |
| `POST` | `/api/v1/mq/bindings` | `201` `400` `401` `403` `404` `409` |
| `GET` | `/api/v1/mq/bindings` | `200` `401` `403` `503` |
| `DELETE` | `/api/v1/mq/bindings` | `204` `400` `401` `403` `404` `409` |

Un binding no tiene nombre propio —lo identifica la tripleta `(exchange, destino, routing key)`—
así que su baja lleva los tres valores como parámetros de consulta en lugar de un identificador en
la ruta. Es la única desviación del patrón `/recurso/{nombre}`, y la impone el modelo de AMQP.

`GET /api/v1/mq/exchanges/{nombre}` devuelve el tipo, la durabilidad, los argumentos y si el
exchange está protegido. Al contrario que la consulta de una cola, va por la API de gestión: AMQP no
tiene una operación para leer un exchange, y la declaración pasiva solo dice si existe.

## La protección de la topología de AndesStay

Las nueve colas y los tres exchanges del caso están en una lista de **protegidos**
(`andesstay.mq.protegidos`, en `application.yml`). Esta API no los modifica:

| Operación | Respuesta |
|---|---|
| Eliminar o purgar una cola protegida | `409 RECURSO_PROTEGIDO` |
| Eliminar un exchange protegido | `409 RECURSO_PROTEGIDO` |
| Crear un binding **hacia** una cola o un exchange protegido | `409 RECURSO_PROTEGIDO` |
| Eliminar un binding que **sale de** un exchange protegido o **llega a** un recurso protegido | `409 RECURSO_PROTEGIDO` |
| Crear un binding **desde** un exchange protegido hacia una cola nueva | `201`: solo agrega una copia de los mensajes |

**`?forzar=true` no levanta la protección.** Cambiar la topología del sistema exige cambiar la lista
y volver a desplegar, de modo que no pueda ocurrir por un parámetro de más en una petición. Borrar
`q.cmd.email` deja a `ms-andesstay-notify` sin su cola de trabajo y tira las notificaciones del
sistema entero, y en RabbitMQ borrar una cola es **irreversible**: se van los mensajes que tuviera
dentro. Quitar `cmd.direct -> q.cmd.email` corta el flujo de correos sin borrar nada visible y sin
que ningún servicio dé error.

El parámetro `forzar` se sigue aceptando en `DELETE` y en la purga, para no romper a quien ya lo
envía. Sobre un recurso no protegido nunca fue necesario.

Dos consecuencias conviene tenerlas presentes:

- **Las DLQ del sistema no se purgan desde aquí.** Tampoco hace falta en el uso normal:
  `DeadLetterConsumer` de notify toma cada mensaje, lo registra en el log y lo confirma, así que la
  DLQ se mantiene vacía. Si alguna vez hubiera que vaciar una, se hace desde la Management UI.
- **Un binding creado desde un exchange protegido no se puede quitar por la API**, porque sale de
  un exchange protegido. Desaparece al eliminar su cola de destino, que sí es libre.

El aviso de `409` dice dónde se decide la protección:

```json
{"error":"RECURSO_PROTEGIDO","detalle":"No se permite eliminar la cola 'q.cmd.email': forma parte de la topología de AndesStay (andesstay.mq.protegidos) y esta API no la modifica, ni siquiera con ?forzar=true","nombre":"q.cmd.email"}
```

## Validación de formato

Los nombres y las routing keys se validan con `@Pattern` antes de llegar al broker, en el cuerpo de
las altas y en los parámetros de la baja de un binding. Los formatos están en
`dto/Restricciones.java`:

| Campo | Formato |
|---|---|
| `nombre` de cola o exchange, `exchange` y `destino` de un binding | Letras sin tilde, dígitos y `. _ - :`, hasta 255 caracteres |
| `routingKey` | Sin espacios, hasta 255 caracteres; admite los comodines `*` y `#` |
| `tipoDestino` | `QUEUE` o `EXCHANGE`, sin distinguir mayúsculas; `QUEUE` si se omite |

RabbitMQ admite casi cualquier carácter en un nombre, pero un nombre con espacios, barras o tildes
complica las URL de esta API y de la consola, y casi siempre es un error de tipeo. El conjunto es
el que ya usa la topología (`q.cmd.email.dlq`, `cmd.dead.dlx`).

Todos los errores de formato responden igual, con el campo que falla:

```json
{"error":"VALIDACION","detalle":"el cuerpo no es válido","campos":{"tipoDestino":"debe ser QUEUE o EXCHANGE","routingKey":"no puede contener espacios"}}
```

Los nombres `amq.*` pasan el formato a propósito: no son inválidos sino reservados, y los rechaza
`NombresAmqp` con `400 PETICION_INVALIDA` y un mensaje que explica el motivo.

## Códigos de error

Todas las respuestas de error tienen la forma `{"error": "<CÓDIGO>", "detalle": "<texto>", …}`,
con campos adicionales según el caso.

| HTTP | `error` | Cuándo |
|---|---|---|
| `400` | `VALIDACION` | Un campo del cuerpo o un parámetro no cumple el formato. Trae `campos` |
| `400` | `CUERPO_ILEGIBLE` | JSON mal formado, o un `tipo` de exchange que no es `DIRECT`, `TOPIC`, `FANOUT` ni `HEADERS` |
| `400` | `PETICION_INVALIDA` | Nombre reservado `amq.*`, o desenlazar del exchange por omisión |
| `401` | — | Sin token, o token inválido o vencido. Sin cuerpo |
| `403` | `ROL_INSUFICIENTE` | Token válido sin el rol `Admin` |
| `404` | `RECURSO_NO_ENCONTRADO` | El recurso no existe. Trae `recurso` y `nombre`, o `detalleDelBroker` |
| `409` | `RECURSO_PROTEGIDO` | Operación sobre la topología de AndesStay. Trae `nombre` |
| `409` | `TOPOLOGIA_INCOMPATIBLE` | Redeclarar con otra definición (`PRECONDITION_FAILED`). Trae `detalleDelBroker` |
| `409` | `RECURSO_BLOQUEADO` | Cola exclusiva de otra conexión |
| `502` | `BROKER_RECHAZO_ACCESO` | Las credenciales del servicio no tienen permiso sobre el vhost |
| `502` | `ERROR_DEL_BROKER` | Otro rechazo del broker. Trae `codigoAmqp` cuando lo hay |
| `502` | `GESTION_RECHAZO` | La API de gestión respondió con error (usuario sin `administrator`, vhost inexistente) |
| `503` | `BROKER_NO_DISPONIBLE` | Sin conexión AMQP |
| `503` | `GESTION_NO_DISPONIBLE` | El puerto 15672 no responde |

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

Con RabbitMQ 3.13, que es la versión del compose, `queue.delete` y `exchange.delete` son
**idempotentes**: borrar algo que no existe responde bien. En la prueba contra el broker real,
`DELETE` sobre una cola o un exchange inexistentes respondió `204` y no `404`. La purga y las
consultas sí responden `404`.

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
configuración sería perder justo lo que aporta. Tampoco la levanta `?forzar=true`: ver
*La protección de la topología de AndesStay*.

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

Cubren, sin broker ni tokens reales: la matriz de códigos HTTP del recurso de colas, la validación
de formato del cuerpo y de los parámetros, la protección de la topología (también con
`forzar=true`), la consulta de un exchange contra la API de gestión, la traducción de los códigos de
cierre de canal de AMQP y las trampas del recurso de bindings.

## Contratos

Los exchanges, bindings y esquemas de mensaje son canónicos y viven en
[`infra/docs/contracts/events/rabbit.md`](https://github.com/AndesStay-Duoc/infra/blob/develop/docs/contracts/events/rabbit.md).

## Cómo contribuir

Ver [`CONTRIBUTING.md`](CONTRIBUTING.md).
