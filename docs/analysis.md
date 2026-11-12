# Análisis prueba técnica VidaCruz – Dev WebFlux

## Veredicto

La prueba está pensada para nivel **Senior / Arquitecto**: dos microservicios reactivos (Triage y Asistente Clínico) conectados por Kafka, con RAG, resiliencia, observabilidad, seguridad, pruebas, docker-compose, Kubernetes **y un despliegue real en Azure con URL pública**. En 3 días no se puede todo perfecto, así que hay que priorizar lo que más pesa en la evaluación.

Lo que más se evalúa, además de que funcione:

- **Cero llamadas bloqueantes** en el event loop. Es la penalización explícita, y Spring AI bloquea por defecto (ver Trampas técnicas).
- **Calidad del SQL del RF-05**: window functions, `FILTER` y percentiles en la base de datos, no en memoria.
- **Anti-alucinación del RAG** y **modo degradado 503** con circuit breaker.
- **Decisiones documentadas**: RF-06 está abierta a propósito, y las ambigüedades se evalúan por cómo las justificas en ADRs.
- **Historial de commits progresivo**: no subir todo en un solo commit al final.

**Riesgos principales:**

1. **Azure (entregable 4).** Exige una URL desplegada. Crear la cuenta, verificar tarjeta y montar Postgres, Kafka y Redis toma tiempo.
2. **WebFlux sin experiencia previa.** El reto más grande es no bloquear el event loop ni romper la cadena reactiva.
3. **Proveedor de LLM.** Necesitas una API key con saldo (OpenAI, Azure OpenAI o Gemini). El costo de la prueba es de centavos.

Nota de nombres: el código va en inglés (clases, paquetes, tablas), pero el **contrato de la API es fijo en español** (`/triage/pacientes`, `nivel`, `slaMinutos`, `fuentes`, `REGISTRADO`, `paciente_registrado`, `triaje.eventos`). Se respeta con DTOs y `@JsonProperty`; no se traduce el contrato.

## Alcance acordado

Se entrega un alcance reducido pero sólido, y lo que falte se explica en el README como decisión de prioridad. Si sobra tiempo, se avanza en el orden del segundo nivel.

**Nivel 1 — Imprescindible**

- Triage RF-01: registro, validaciones (400 con el campo), clasificación RN-01 y auditoría.
- Triage RF-02: máquina de estados con 404, 400 y 409; GET con flag de escalado y tiempo en estado.
- Triage RF-03: scheduler con `UPDATE` atómico y SLA en properties.
- Eventos a Kafka después de guardar (sin Outbox; limitación documentada).
- Triage RF-05: reporte 100% en SQL.
- Asistente RF-04 B: consumidor Kafka hacia Redis y endpoint de estado.
- Asistente RF-04 A: RAG con vector store en memoria, umbral, "sin protocolo" y circuit breaker con 503.
- Tests obligatorios (E1–E6, transiciones, idempotencia, RAG con LLM simulado) y `docker compose up --build`.
- README con sección "Alcance entregado" y mínimo 2 ADRs.

**Nivel 2 — Si queda tiempo (en este orden)**

1. API key por header y health checks (liveness/readiness).
2. Métricas básicas y logs con traceId.
3. Manifests de Kubernetes sin desplegar.
4. Patrón Outbox.
5. RF-06 con SSE.
6. Despliegue en Azure.

**Nivel 3 — Fuera de alcance, solo documentado**

- RF-06: ADR con la propuesta de diseño (si no se llegó a implementar).
- BlockHound, OpenTelemetry, pgvector, CI en GitHub Actions: sección "Qué haría con más tiempo".

## Requerimientos funcionales

| RF | Qué exige | Detalles fáciles de pasar por alto |
| --- | --- | --- |
| RF-01 Registro | `POST /triage/pacientes` con 10 campos; valida rangos; clasifica; responde `id`, `nivel`, `slaMinutos`, `estado: REGISTRADO`, `fechaRegistro` ISO 8601; publica `paciente_registrado` | El 400 debe **nombrar el campo inválido**. Diastólica **estrictamente menor** que sistólica (validación cruzada). Campos faltantes, `null` o tipo incorrecto también son 400 con mensaje claro. La queja principal debe ser obligatoria. El registro también genera **auditoría**. |
| RF-02 Estados | `PATCH /triage/pacientes/{id}/estado`; transiciones válidas; 409 si es inválida; auditoría y evento por transición; `GET /triage/pacientes/{id}` | **404** si el id no existe, **400** si el estado no es un valor válido, **409** si la transición no está permitida (incluye pasar al mismo estado). El GET pide **flags** (escalado) y **tiempo en el estado actual**: guardar la fecha del último cambio de estado. Dos PATCH simultáneos no deben dejar datos inconsistentes. |
| RF-03 SLA | Proceso de fondo que revisa pacientes en `REGISTRADO`; si superan su SLA los marca `ESCALADO`, publica `paciente_escalado` y audita | Intervalo y SLA **configurables por properties**. Idempotente (RN-03). Con **varias réplicas** cada pod corre el scheduler: sin un `UPDATE` atómico se duplican eventos. Carrera entre escalar y pasar a `EN_CONSULTA`. El flag es persistente. |
| RF-04 A RAG | `POST /asistente/consulta` responde `respuesta` y `fuentes[{protocolo, seccion}]`; cita obligatoria; sin coincidencia, aviso explícito | Fragmentar el corpus **por sección** y guardar esa metadata. Las fuentes se construyen desde los documentos recuperados, no desde lo que diga el LLM. Si no hay coincidencia, no se llama al LLM. |
| RF-04 B Estado urgencias | `GET /asistente/estado-urgencias`: pacientes activos (todo lo que no esté en `ALTA`) por nivel | Se reconstruye **solo desde Kafka**. Persistido en Redis. `EN_CONSULTA` **sigue contando**. Escalar no cambia el nivel. Consumidor idempotente. Devolver los 5 niveles, incluso en cero. |
| RF-05 Reporte | `GET /triage/reportes/operacion?desde&hasta` agrupado por hora: total, conteo por nivel, espera promedio y **p95** (registro hasta primera entrada a consulta, en minutos) | **Prohibido calcular en memoria.** Usar `date_trunc`, `COUNT(*) FILTER`, `ROW_NUMBER() OVER` y `percentile_cont(0.95) WITHIN GROUP`. Validar `desde < hasta` e ISO 8601 (400 si no). Pacientes sin consulta cuentan en el total pero no en espera y p95. |
| RF-06 Alertas | Alertar al equipo médico cuando un paciente se escala | Abierto a propósito: se evalúa la decisión, los supuestos y el ADR tanto como el código. |

## Reglas de negocio y casos borde

| Caso | Qué regla lo define | Nivel |
| --- | --- | --- |
| E1 | FC 130 > 120 (también dolor 8, de nivel 3; rige el más grave) | CRITICO |
| E2 | SpO2 91 entre 90 y 93 (también FC 95, de nivel 3) | URGENTE |
| E3 | Temperatura 38.2 entre 38.0 y 38.5 | URGENTE_2 |
| E4 | Nada se cumple; "Herida por cortar" no es queja administrativa | NO_URGENTE |
| E5 | Nivel 4 + "Renovación de recetario" está en la lista administrativa | NO_URGENTE_2 |
| E6 | FC 35, SpO2 85, sistólica 80 y GCS 6 | CRITICO |

- Evaluar de nivel 1 a 3 y devolver el primero que se cumpla.
- El nivel 5 **solo aplica si el paciente ya es nivel 4**. Queja administrativa con fiebre de 38.2 = URGENTE_2. Merece su propio test.
- Comparar la queja normalizada (minúsculas, sin tildes, sin espacios extra) con coincidencia exacta.
- Huecos no cubiertos por las reglas (hipotermia, hipertensión, edad): se aplica el enunciado tal cual y se documenta como supuesto.
- `BigDecimal` para la temperatura.

**Valores límite a probar**

| Signo | Límite | Nivel |
| --- | --- | --- |
| FC | 39 / 40 / 89 / 90 / 120 / 121 | 1 / 4 / 4 / 3 / 3 / 1 |
| SpO2 | 89 / 90 / 93 / 94 | 1 / 2 / 2 / 4 |
| Sistólica | 89 / 90 / 100 / 101 | 1 / 2 / 2 / 4 |
| Temperatura | 37.9 / 38.0 / 38.4 / 38.5 | 4 / 3 / 3 / 2 |
| GCS | 8 / 9 / 13 / 14 | 1 / 2 / 2 / 4 |
| Dolor | 6 / 7 | 4 / 3 |

También probar límites de validación: FC 19 y 251, diastólica igual a sistólica, nombre de 2 y 101 caracteres, edad 0 y 121. La tabla de SLA (RN-02) va en properties.

**RN-03 y RN-04:** el escalamiento se hace una sola vez y nunca en `EN_CONSULTA` o `ALTA`. La auditoría registra fecha, id, estado anterior, estado nuevo y tipo de evento para registro, consulta, escalamiento y alta. En el registro el estado anterior es `null`. En el escalamiento el estado no cambia (`REGISTRADO` a `REGISTRADO`) y el tipo de evento lo identifica. Sin DELETE; como plus, trigger que bloquee `UPDATE` y `DELETE` en auditoría.

## Trampas técnicas

**Programación reactiva**

- **Spring AI es bloqueante.** `chatClient.prompt().call()` y `vectorStore.similaritySearch()` bloquean el hilo: envolverlos en `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`.
- La ingesta del corpus al arrancar también bloquea: hacerla fuera del event loop. Si el LLM está caído al arrancar, el servicio debe iniciar igual.
- Nunca `.block()`, `Thread.sleep` ni JDBC en los handlers.
- Flyway usa JDBC al arrancar, antes del servidor: está bien, mencionarlo en el README.
- R2DBC no tiene relaciones ni lazy loading. Consultas complejas (RF-05) con `DatabaseClient` y SQL nativo.
- Transacciones reactivas con `TransactionalOperator` o `@Transactional` sobre métodos que devuelven `Mono`/`Flux`.

**Kafka y consistencia**

- **Doble escritura.** Guardar y luego publicar puede dejar uno sin el otro. Nivel 1: publicar después del commit detrás de un puerto; nivel 2: patrón Outbox.
- **Clave del mensaje = id del paciente** para mantener el orden por paciente.
- Los eventos llevan el **nivel** (también `paciente_alta`), un `eventId` y la fecha.
- **Reactor Kafka está descontinuado** (anunciado por Spring en mayo de 2025). Decisión: Spring Kafka normal, listeners que devuelven Mono en el consumidor y `KafkaTemplate` envuelto en `Mono.fromFuture` para publicar.
- Consumidor del Asistente **idempotente**: sets de Redis por nivel (`SADD` / `SREM`) en vez de contadores; el conteo es `SCARD`.

**Concurrencia del escalamiento**

- Un solo `UPDATE` atómico: `UPDATE patients SET escalated = true, escalated_at = now() WHERE id = :id AND status = 'REGISTRADO' AND escalated = false RETURNING *`. Sin fila, no se publica nada.
- Resuelve idempotencia, carrera con el PATCH y varias réplicas.
- Para el PATCH: `WHERE status = :expectedStatus` o `@Version`.
- Inyectar `Clock`. Usar `timestamptz` e `Instant` (UTC).

**RAG y anti-alucinación**

- Fragmentar por sección con metadata `protocolo` y `seccion`.
- Umbral de similitud: sin fragmentos por encima, responder "No existe un protocolo disponible" con `fuentes: []` sin llamar al LLM.
- Prompt estricto, marca fija `SIN_PROTOCOLO` si el contexto no alcanza, temperatura 0.
- `fuentes` desde la metadata recuperada, validadas contra el corpus.
- Calibrar el umbral con las 3 preguntas de ejemplo (la de diabetes debe quedar por debajo).
- Circuit breaker sobre chat y embeddings, con timeout. Abierto o fallando: 503 con mensaje claro.
- La readiness del Asistente no depende del LLM.

**Observabilidad y seguridad**

- Micrometer Tracing + `Hooks.enableAutomaticContextPropagation()` para tener traceId en logs con WebFlux.
- Logs estructurados de Spring Boot 3.4+ (`logging.structured.format.console=ecs`).
- Métricas: clasificaciones por nivel, escalamientos, latencia del LLM.
- Credenciales en variables de entorno: `.env.example` versionado, `.env` ignorado.
- API key por header `X-API-Key`; health público.

## Ambigüedades y decisiones a documentar

| Ambigüedad | Decisión recomendada | Dónde |
| --- | --- | --- |
| RF-06: canal de alertas | Consumidor de `paciente_escalado` que emite por Server-Sent Events (`GET /alertas/stream`) y opcionalmente webhook de Teams, detrás de una interfaz | ADR |
| ¿Dónde vive RF-06? | Módulo separado dentro del Asistente, extraíble a un tercer servicio | ADR |
| 12 salas de urgencias | El contrato no tiene sede: un solo tablero; `sede` como evolución | README |
| `nivel` en la respuesta | Nombre (`"CRITICO"`) | README |
| Tiempo en estado actual | Duración ISO 8601 (`PT12M30S`) calculada al consultar | README |
| Quejas administrativas | Normalizar y comparar exacto | README |
| SLA si cambia la configuración | Se guarda el SLA del paciente al registrarlo | README |
| "Superó su SLA" | Estrictamente mayor | README |
| RF-05: agrupación | Por hora de registro en UTC; `desde` inclusivo, `hasta` exclusivo; sin consulta: en totales, no en promedio/p95 | README |
| Formato de eventos | JSON con `eventId`, `eventType`, `occurredAt`, `patientId`, `level`, `status`; clave `patientId` | ADR |
| Vector store | En memoria (`SimpleVectorStore`); pgvector como evolución | ADR |
| Autenticación | API key por header; JWT con Entra ID como evolución | README |
| Respuesta "sin protocolo" | `fuentes: []` (no es contenido clínico) | README |
| Auditoría del escalamiento | `REGISTRADO` → `REGISTRADO`, tipo `ESCALAMIENTO` | README |

## Arquitectura propuesta

Dos microservicios que solo se comunican por Kafka; Triage nunca depende del Asistente.

- **triage-service**: REST (registro, estados, reporte), clasificación RN-01, scheduler SLA con `UPDATE` atómico, auditoría, publicación de eventos. Base de datos: PostgreSQL (R2DBC).
- **Kafka**: topic `triaje.eventos`, clave `patientId`.
- **assistant-service**: REST (consulta y estado-urgencias), consumidor de eventos hacia Redis, RAG con umbral y citas, circuit breaker con 503, alertas RF-06.
- **Redis**: sets de pacientes activos por nivel.
- **Proveedor LLM**: chat + embeddings, API key por variable de entorno.

Organización interna hexagonal:

- `domain`: `Patient`, `TriageLevel`, `PatientStatus`, `TriageClassifier`, reglas de transición.
- `application`: `RegisterPatient`, `ChangePatientStatus`, `EscalateOverduePatients`, `GetOperationReport`.
- `infrastructure`: controladores WebFlux, repositorios R2DBC, publicador Kafka, scheduler, configuración.

| Tabla | Columnas clave |
| --- | --- |
| `patients` | `id`, signos vitales, `chief_complaint`, `level`, `sla_minutes`, `status`, `escalated`, `escalated_at`, `registered_at`, `status_changed_at`, `version` |
| `audit_entries` | `id`, `patient_id`, `previous_status`, `new_status`, `event_type`, `occurred_at` (solo inserción) |
| `outbox_events` (nivel 2) | `id`, `aggregate_id`, `event_type`, `payload` (JSONB), `created_at`, `published_at` |

Eventos: `paciente_registrado`, `paciente_en_consulta`, `paciente_alta`, `paciente_escalado`.

## Plan de 3 días

Cada tarea termina con tests pasando y un commit de Sebastián.

**Día 1 — Triage base**

- [ ] Proyecto Maven multi-módulo, `docker-compose` con Postgres, Kafka (KRaft) y Redis, `.env.example`.
- [ ] Migración inicial: `patients`, `audit_entries`.
- [ ] Clasificador RN-01 como dominio puro + tests E1–E6 y valores límite.
- [ ] RF-01: endpoint de registro, validaciones con `ProblemDetail`, auditoría.
- [ ] RF-02: PATCH de estado (404/400/409), GET con flags y tiempo en estado, tests de transiciones.

**Día 2 — Escalamiento, reporte y eventos**

- [ ] Publicación de los 4 eventos en `triaje.eventos` (clave `patientId`).
- [ ] RF-03: scheduler con `UPDATE` atómico + test de idempotencia.
- [ ] RF-05: consulta SQL del reporte + test con datos sembrados.
- [ ] Asistente RF-04 B: consumidor Kafka, sets en Redis, `GET /asistente/estado-urgencias`.

**Día 3 — RAG y entrega**

- [ ] RF-04 A: corpus por sección, umbral, "sin protocolo", fuentes validadas.
- [ ] Circuit breaker + timeout con 503 + tests con LLM simulado (las 3 preguntas de ejemplo).
- [ ] Dockerfiles y `docker compose up --build` levantando todo.
- [ ] README (alcance entregado, cómo correr, decisiones, qué haría con más tiempo) y ADRs.
- [ ] Revisar que no haya credenciales en el historial y enviar.

Lo que quede del nivel 2 se intenta solo después de cerrar el día 3.

## Checklist de entregables

- [ ] Repositorio en GitHub con historial de commits progresivo.
- [ ] README: un comando para correr local, arquitectura, tecnologías, decisiones, supuestos y alcance entregado.
- [ ] Tests obligatorios: E1–E6, transiciones, idempotencia del escalamiento, citas del RAG y "sin coincidencia sin alucinación", con el LLM simulado.
- [ ] Mínimo 2 ADRs.
- [ ] `docker-compose` que levante Postgres, Kafka, Redis y ambos servicios.
- [ ] Ninguna credencial en el repo ni en el historial.
- [ ] Endpoints exactos: `/triage/pacientes`, `/triage/pacientes/{id}/estado`, `/triage/reportes/operacion`, `/asistente/consulta`, `/asistente/estado-urgencias`.
- [ ] Topic `triaje.eventos` y eventos `paciente_registrado`, `paciente_en_consulta`, `paciente_alta`, `paciente_escalado`.
- [ ] (Nivel 2) URL pública en Azure y manifests de Kubernetes.
