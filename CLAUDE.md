# VidaCruz – Sistema de triaje digital (prueba técnica Senior WebFlux)

Fuente de verdad: `docs/challenge.txt` (enunciado) y `docs/analysis.md` (análisis, decisiones y plan).
Antes de implementar cualquier RF, lee su sección en ambos archivos. Si algo del enunciado no está claro,
NO inventes: propón una decisión, regístrala en `docs/assumptions.md` y en un ADR si es arquitectónica.

## Alcance acordado (respetar estrictamente)
- Nivel 1 (implementar): RF-01, RF-02, RF-03, RF-05, eventos a Kafka, RF-04 B, RF-04 A con vector store en
  memoria y circuit breaker/503, tests obligatorios, docker-compose, README con "Alcance entregado" y 2+ ADRs.
- Nivel 2 (solo si Sebastián lo pide, en este orden): API key + health, métricas/logs con traceId, manifests K8s,
  Outbox, RF-06 con SSE, despliegue en Azure.
- Nivel 3 (NO implementar, solo documentar): BlockHound, OpenTelemetry, pgvector, CI.
- No adelantes trabajo de nivel 2 o 3 aunque parezca fácil. Si algo de nivel 1 lo necesita, pregunta.

## Stack fijo (no cambiar sin preguntar)
- Java 21, Spring Boot 3.5.x (NO Spring Boot 4), Maven multi-módulo.
- Spring WebFlux + Project Reactor (Mono/Flux). R2DBC + PostgreSQL. Flyway para migraciones.
- Spring Kafka (NO reactor-kafka: está descontinuado). Consumidores con listeners que devuelven Mono.
- Redis reactivo (ReactiveRedisTemplate) para la vista del Asistente.
- Spring AI 1.1.x (compatible con Boot 3.5). NO Spring AI 2.x.
- Resilience4j (circuit breaker + timeout) con el operador de Reactor.
- Micrometer + Actuator. Testcontainers, StepVerifier, WebTestClient.

## Módulos
- `triage-service`: RF-01, RF-02, RF-03, RF-05. Dueño de PostgreSQL.
- `assistant-service`: RF-04 A (RAG), RF-04 B (vista desde Kafka en Redis), RF-06 (alertas).
- Los servicios SOLO se comunican por Kafka (topic `triaje.eventos`). El Asistente nunca llama a la API ni a la BD de Triage.

## Convenciones de código
- Todo el código en inglés: paquetes, clases, métodos, variables, tablas y columnas.
- EXCEPCIÓN: el contrato público va exactamente como el enunciado, en español:
  rutas (`/triage/pacientes`, `/asistente/consulta`...), campos JSON (`nivel`, `slaMinutos`, `fechaRegistro`,
  `estado`, `respuesta`, `fuentes`, `protocolo`, `seccion`, `pregunta`), valores (`REGISTRADO`, `EN_CONSULTA`,
  `ALTA`, `CRITICO`...), topic `triaje.eventos` y eventos `paciente_registrado`, `paciente_en_consulta`,
  `paciente_alta`, `paciente_escalado`. Usa DTOs con `@JsonProperty`; el dominio sigue en inglés.
- Valores internos en inglés (dominio y BD, guardados con `name()` del enum): estado `REGISTERED`, `IN_CONSULTATION`,
  `DISCHARGED`; nivel `CRITICAL`, `URGENT`, `URGENT_2`, `NON_URGENT`, `NON_URGENT_2`; tipo de auditoría
  `PATIENT_REGISTERED`, `PATIENT_IN_CONSULTATION`, `PATIENT_DISCHARGED`, `PATIENT_ESCALATED`. Los valores del contrato
  en español se mapean SOLO en la frontera (DTOs de la API y eventos de Kafka) con un mapper en `infrastructure`.
- Arquitectura hexagonal por servicio: `domain` (sin Spring), `application` (casos de uso, puertos), `infrastructure` (adaptadores).
- Fechas: `Instant` en UTC y `timestamptz`. Inyecta `Clock`; nunca `Instant.now()` directo en la lógica.
- Temperatura con `BigDecimal`.
- Errores HTTP con `ProblemDetail`: 400 nombra el campo inválido, 404 id inexistente, 409 transición inválida, 503 LLM no disponible.

## Reglas reactivas (penalizadas si se rompen)
- Prohibido en código de producción: `.block()`, `Thread.sleep`, JDBC/JPA, `subscribe()` dentro de handlers.
- Toda llamada bloqueante (Spring AI `call()`, `similaritySearch()`, `KafkaTemplate.send` si bloquea)
  va en `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`.
- La ingesta del corpus al arrancar no debe bloquear el event loop ni impedir el arranque si el LLM falla.
- Revisa cada handler y adaptador buscando llamadas bloqueantes antes de dar una tarea por terminada.

## Reglas de negocio críticas
- RN-01: evaluar nivel 1 → 3, el primero que cumpla gana. Nivel 5 solo si ya es nivel 4 y la queja normalizada
  (minúsculas, sin tildes, trim) coincide EXACTO con la lista administrativa.
- SLA e intervalo del scheduler en `application.yml` (`triage.sla.*`, `triage.escalation.interval`). El SLA del
  paciente se guarda al registrarlo. "Superó SLA" = `now > registeredAt + sla` (estricto).
- Escalamiento: un único `UPDATE ... WHERE status='REGISTERED' AND escalated=false RETURNING`; si no devuelve
  fila, no se publica evento ni se audita (RN-03, carreras y múltiples réplicas).
- Paciente + auditoría en la MISMA transacción; el evento se publica a Kafka después del commit (nivel 1).
  Diseña el publicador detrás de un puerto (`PatientEventPublisher`) para poder cambiarlo a Outbox (nivel 2) sin tocar los casos de uso.
- Clave de Kafka = `patientId`. Todo evento lleva `eventId`, `eventType`, `occurredAt`, `patientId`, `level`, `status`.
- Auditoría: solo inserción, sin endpoint DELETE, trigger que bloquee UPDATE/DELETE.
- RF-05: agregados 100% en SQL (`date_trunc`, `COUNT(*) FILTER`, `ROW_NUMBER() OVER`, `percentile_cont`). Nada en memoria.
- Vista del Asistente: sets de Redis por nivel (`SADD`/`SREM` del id) para ser idempotente; `EN_CONSULTA` sigue activo.
- RAG: fragmentos por sección con metadata `protocolo` y `seccion`; umbral de similitud; sin coincidencia →
  aviso fijo "no existe protocolo disponible", `fuentes: []` y SIN llamar al LLM; fuentes construidas desde los
  documentos recuperados y validadas contra el corpus. Temperatura 0. Readiness NO depende del LLM.

## Seguridad
- Ninguna credencial en el código ni en commits. Variables de entorno; `.env` está en `.gitignore`, `.env.example` sí se versiona.
- Autenticación por header `X-API-Key`; endpoints de actuator/health públicos.

## Flujo de trabajo
- Trabaja UNA tarea pequeña a la vez (del plan en `docs/analysis.md`). Para tareas grandes, propone un plan antes de editar.
- Cada tarea termina con: código + tests pasando (`./mvnw -q verify` en el módulo afectado) + resumen de lo hecho.
- No hagas commit ni push: Sebastián revisa y commitea él mismo (Conventional Commits, en inglés).
- No agregues dependencias ni cambies versiones sin decir por qué.
- Si una decisión no está en este archivo ni en `docs/analysis.md`, pregunta antes de implementar.

## Comandos
- Infra local: `docker compose up -d postgres kafka redis`
- Todo el sistema: `docker compose up --build`
- Tests: `./mvnw verify` (requiere Docker corriendo para Testcontainers)
