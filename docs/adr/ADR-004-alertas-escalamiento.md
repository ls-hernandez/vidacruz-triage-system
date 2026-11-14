# ADR-004: Alertas de escalamiento al equipo médico (RF-06)

- **Estado:** aceptado
- **Fecha:** 2026-11-13
- **Relacionado:** RF-03 (escalamiento), RF-04 B (consumo de `triaje.eventos`), supuestos 24–26 y 40

## Contexto

RF-06 pide alertar al equipo médico cuando un paciente es escalado. El enunciado deja abiertos el canal, el formato y la tecnología.

Ya existe un punto natural para hacerlo. Triage publica `paciente_escalado` en `triaje.eventos` justo después de confirmar el escalamiento con el `UPDATE` condicional. Ese `UPDATE` garantiza un solo evento por paciente, aunque haya varias réplicas (RN-03, supuesto 19). El Asistente ya consume ese topic para la vista de RF-04 B. La regla del proyecto es que los servicios solo se comunican por Kafka: Triage no debe saber quién recibe las alertas.

Suponemos que el destinatario principal es un **tablero en vivo** del servicio de urgencias, una pantalla o navegador que el equipo médico mira durante el turno. Lo que el equipo necesita es enterarse en segundos de que un paciente superó su SLA. El estado actual de cada paciente ya está disponible en otros endpoints.

## Opciones consideradas

| Opción | A favor | En contra |
|---|---|---|
| **Server-Sent Events** (`text/event-stream`) | HTTP estándar y unidireccional, que es justo el caso (servidor → tablero). WebFlux lo soporta de forma nativa con `Flux<ServerSentEvent>`. Funciona con la misma API key, los mismos proxies y el mismo puerto. El navegador reconecta solo (`EventSource`). Se prueba con `WebTestClient` y `StepVerifier`. | Sin persistencia, un cliente desconectado pierde las alertas de ese intervalo. Cada conexión es larga, así que con varias réplicas hace falta un fan-out entre instancias. `EventSource` no permite headers propios: un navegador necesita un proxy o un polyfill que envíe `X-API-Key`. |
| **WebSocket** | Bidireccional y con baja latencia. | La bidireccionalidad no se usa (el tablero no envía nada). Necesita un protocolo propio encima (o STOMP) y una autenticación en el *handshake*, y los proxies lo tratan de forma distinta. Es más código y más pruebas para el mismo resultado. |
| **Microsoft Teams (webhook entrante / Workflows)** | Llega al canal donde el equipo ya conversa, con historial y notificaciones en el móvil. | Depende de un tercero y de un secreto por canal (la URL del webhook). La entrega es una llamada HTTP saliente con su propia latencia, sus límites de tasa y sus fallos (reintentos, circuit breaker). No se puede demostrar sin un tenant. Además, enviar datos de pacientes a una herramienta de chat requiere una revisión de privacidad. |
| **Correo electrónico** | Universal y con historial. | Su latencia (minutos) no sirve para una alerta de urgencias. Requiere un SMTP o un proveedor con credenciales, y los avisos se pierden entre otros correos. |

## Decisión

**SSE en `GET /asistente/alertas/stream`, dentro del Asistente, detrás del puerto `AlertNotifier`.**

- **Origen.** El mismo `PatientEventListener` de RF-04 B llama al caso de uso `NotifyEscalation`, que solo actúa con `paciente_escalado` y crea una `EscalationAlert`. La alerta se emite **antes y fuera** de los reintentos de Redis: un reintento de la vista no duplica la alerta, y una caída de Redis no la retrasa. Un fallo de la alerta se registra en el log y no afecta la vista ni el commit del offset.
- **Canal.** `SseAlertBroadcaster` implementa `AlertNotifier` con `Sinks.many().multicast().directBestEffort()`, sin replay ni buffer compartido.
- **Backpressure por cliente.** Cada conexión lee a través de su propio buffer acotado (`assistant.alerts.client-buffer`, 64) con `DROP_OLDEST`. Si un cliente lento lo llena, pierde sus alertas más antiguas y se registra un `WARN` con el paciente afectado. El sink nunca espera a nadie, así que ni los otros clientes ni el listener de Kafka se frenan.
- **Formato.** Evento SSE `alerta_escalamiento`, con `id` igual al `eventId` del escalamiento y `data` en JSON con las claves del contrato en español:
  ```json
  { "pacienteId": "…", "nivel": "CRITICO", "fechaEscalamiento": "2026-11-13T10:15:00.123456Z",
    "mensaje": "Paciente CRITICO superó su SLA sin ser atendido" }
  ```
- **Heartbeat.** Un comentario SSE `:heartbeat` al conectar (envía los headers enseguida) y luego cada `assistant.alerts.heartbeat` (15 s), para que los proxies y load balancers no cierren una conexión sin tráfico. Los ticks que el cliente no alcanza a leer se descartan.
- **Reprocesamiento del topic.** Los escalamientos con `occurredAt` más antiguo que `assistant.alerts.max-age` (15 min) no se alertan. Solo llegan así cuando se reprocesa el topic, por ejemplo con un grupo nuevo para reconstruir Redis (supuesto 26). Sin este filtro, el tablero recibiría de golpe todos los escalamientos históricos.
- **Seguridad.** El endpoint exige `X-API-Key`, como el resto (supuesto 33).

## Consecuencias

**Positivas**
- No hay infraestructura nueva ni dependencias nuevas. Triage no cambia: el Asistente reutiliza el consumo de `triaje.eventos` que ya tiene.
- Latencia de milisegundos desde el consumo del evento. Probado de punta a punta: un evento publicado en Kafka llega a un cliente SSE conectado, y los demás tipos de evento no generan alerta.
- **Evolución sin tocar el caso de uso.** Para agregar Teams o correo basta con una implementación nueva de `AlertNotifier` y un `CompositeAlertNotifier` que reparta la alerta a todos los canales. Cada canal externo llevaría su propio aislamiento (circuit breaker, timeout y ejecución en `boundedElastic` si su cliente bloquea), para que una caída de Teams no afecte al SSE ni al consumo. No se implementa ahora.
- El módulo de alertas está aislado (`application/NotifyEscalation`, `infrastructure/alerts`, un controlador) y se puede extraer a un tercer servicio con su propio grupo de consumidores.

**Negativas y supuestos aceptados**
- **Sin persistencia ni historial.** Un cliente desconectado (red, recarga, despliegue) **no recibe las alertas de ese intervalo**, y `Last-Event-ID` no se atiende. Es aceptable para un tablero en vivo: la alerta es un aviso, no la fuente de verdad. El dato no se pierde: el paciente sigue escalado (`GET /triage/pacientes/{id}`, flag `escalado`) y sigue contando en `GET /asistente/estado-urgencias`. Al reconectar, el tablero debe recargar ese estado. Si más adelante se necesitara garantizar la entrega o un acuse de recibo, haría falta persistir las alertas (o leerlas desde Kafka con un offset por cliente), y sería otro diseño.
- **Entrega al menos una vez.** Si Kafka reentrega un evento (rebalanceo o reinicio antes del commit), el cliente puede recibir la misma alerta dos veces. **El cliente deduplica por `pacienteId`** (un paciente se escala una sola vez, RN-03) o por el `id` del evento SSE.
- **Una sola réplica.** El sink vive en memoria y el listener comparte el grupo `assistant-service`. Con N réplicas, cada una consume solo sus particiones, así que un tablero conectado a la réplica A no ve los escalamientos de las particiones de B. Con el despliegue actual (una instancia en docker-compose) no aplica. Hay dos evoluciones posibles para escalar horizontalmente:
  1. Un listener de alertas con un **grupo por instancia** (por ejemplo `assistant-alerts-${HOSTNAME}`, `auto-offset-reset: latest`): cada réplica recibe todos los escalamientos.
  2. Un **fan-out por Redis Pub/Sub** entre réplicas.
- **Filtro de antigüedad.** Un escalamiento que se consuma con más de 15 min de retraso (por ejemplo, después de una caída larga del Asistente) no se alerta. Se registra en `DEBUG` y el paciente sigue visible como escalado en la vista. Se puede ajustar con `ASSISTANT_ALERTS_MAX_AGE`.
- **`EventSource` del navegador** no envía headers propios. Un tablero web necesita un proxy que agregue la clave o un cliente SSE con `fetch`. Con JWT (evolución del supuesto 33) se resolvería con una cookie o un token de corta duración.
