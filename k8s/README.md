# Despliegue en AKS

Manifests de Kubernetes para Azure Kubernetes Service, agrupados con Kustomize (`kubectl apply -k k8s/`).
**No se han desplegado ni probado contra un clúster real**: se validaron solo de forma local (ver "Validación").

| Archivo | Contenido |
|---|---|
| `namespace.yaml` | Namespace `vidacruz`. |
| `configmap.yaml` | `vidacruz-config`: configuración no secreta (endpoints de PostgreSQL, Event Hubs y Redis). |
| `triage-service.yaml` | Deployment (2 réplicas) + Service ClusterIP en el puerto 8080. |
| `assistant-service.yaml` | Deployment (1 réplica) + Service ClusterIP en el puerto 8081. |
| `ingress.yaml` | Ingress de AKS application routing (NGINX gestionado): `/triage` y `/asistente`. |
| `kustomization.yaml` | Agrupa todo, fija el namespace y reemplaza las imágenes por las del ACR. |

El Secret `vidacruz-secrets` **no está versionado**: se crea con `kubectl` (paso 3) y `kubectl apply -k` nunca lo pisa.

## Decisiones

- **Dependencias fuera del clúster.** PostgreSQL, Kafka y Redis no se despliegan en AKS: se usan servicios gestionados de Azure (ver "Servicios gestionados"). Los servicios solo reciben endpoints por el ConfigMap y credenciales por el Secret.
- **Réplicas.** `triage-service` corre con 2 réplicas: todas ejecutan el scheduler de escalamiento, pero el `UPDATE ... WHERE status='REGISTERED' AND escalated=false RETURNING` hace que solo una escale a cada paciente (supuesto 19). `assistant-service` corre con 1 réplica porque, con varias, cada pod solo alertaría por SSE los escalamientos de sus particiones (ADR-004, supuesto 40).
- **Probes.** `startupProbe` sobre la liveness con hasta 3 min de margen; `livenessProbe` en `/actuator/health/liveness` (sin dependencias externas); `readinessProbe` en `/actuator/health/readiness` (Triage: PostgreSQL; Asistente: Redis, nunca el LLM). Son públicos, no necesitan `X-API-Key` (supuestos 33 y 34).
- **Recursos.** Memoria request = limit = 768Mi: la imagen usa `-XX:MaxRAMPercentage=75`, así que el heap queda en ~576 MB. CPU request 500m y limit 2 (el límite de CPU también dimensiona los pools de la JVM y acelera el arranque).
- **Seguridad del pod.** Usuario no root (`runAsUser: 100`, `runAsGroup: 101`, el usuario `app` de la imagen, que no es numérico y por eso se indica explícito), sin escalada de privilegios, sin capabilities, `seccompProfile: RuntimeDefault`, sistema de archivos raíz de solo lectura con `/tmp` en `emptyDir`, y sin token de service account montado.
- **Secretos por clave.** Cada contenedor recibe solo las claves del Secret que necesita (`secretKeyRef`), no el Secret completo.
- **Perfil.** No se fija `SPRING_PROFILES_ACTIVE`: el perfil por defecto es el de producción (logs JSON ECS). El perfil `local` no debe activarse en el clúster.

## Pasos

### 1. Requisitos

- Un clúster AKS con el add-on **application routing** habilitado (`az aks approuting enable -g <rg> -n <aks>`).
- Un Azure Container Registry asociado al clúster, para que los nodos puedan descargar las imágenes sin `imagePullSecrets`:
  ```bash
  az aks update -g <rg> -n <aks> --attach-acr <acr-name>
  ```
- Los servicios gestionados creados y accesibles desde la red del clúster (ver "Servicios gestionados").
- `kubectl` apuntando al clúster: `az aks get-credentials -g <rg> -n <aks>`.

### 2. Construir y subir las imágenes al ACR

Desde la raíz del repositorio (el contexto de build es la raíz; ver supuesto 38). Antes, correr los tests con `./mvnw verify`, porque el build de la imagen no los ejecuta.

```bash
ACR=<acr-name>.azurecr.io
TAG=$(git rev-parse --short HEAD)

az acr login --name <acr-name>

docker build --platform linux/amd64 -f triage-service/Dockerfile -t $ACR/vidacruz/triage-service:$TAG .
docker build --platform linux/amd64 -f assistant-service/Dockerfile -t $ACR/vidacruz/assistant-service:$TAG .

docker push $ACR/vidacruz/triage-service:$TAG
docker push $ACR/vidacruz/assistant-service:$TAG
```

Después, reemplazar `<acr-name>` y `<tag>` en la sección `images` de `k8s/kustomization.yaml` (o, con la CLI de kustomize: `cd k8s && kustomize edit set image triage-service=$ACR/vidacruz/triage-service:$TAG assistant-service=$ACR/vidacruz/assistant-service:$TAG`).

Reemplazar también los `<placeholders>` de `k8s/configmap.yaml` con los nombres reales de los servicios gestionados.

### 3. Crear el namespace y el Secret

```bash
kubectl apply -f k8s/namespace.yaml

# Leer los valores sin que queden en el historial de la shell
read -rs TRIAGE_API_KEY;    echo
read -rs ASSISTANT_API_KEY; echo
read -rs OPENAI_API_KEY;    echo
read -rs POSTGRES_PASSWORD; echo
read -rs EVENTHUBS_CONNECTION_STRING; echo   # Event Hubs > Shared access policies > Connection string
read -rs REDIS_PASSWORD;    echo             # Azure Cache for Redis > Access keys > Primary

kubectl create secret generic vidacruz-secrets -n vidacruz \
  --from-literal=TRIAGE_API_KEY="$TRIAGE_API_KEY" \
  --from-literal=ASSISTANT_API_KEY="$ASSISTANT_API_KEY" \
  --from-literal=OPENAI_API_KEY="$OPENAI_API_KEY" \
  --from-literal=POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
  --from-literal=KAFKA_SASL_JAAS_CONFIG="org.apache.kafka.common.security.plain.PlainLoginModule required username=\"\$ConnectionString\" password=\"$EVENTHUBS_CONNECTION_STRING\";" \
  --from-literal=REDIS_PASSWORD="$REDIS_PASSWORD"
```

| Clave | Servicio | Obligatoria |
|---|---|---|
| `TRIAGE_API_KEY` | triage | Sí (sin ella el servicio no arranca). |
| `POSTGRES_PASSWORD` | triage | Sí. |
| `ASSISTANT_API_KEY` | assistant | Sí. |
| `OPENAI_API_KEY` | assistant | No. Si falta, el servicio arranca y el RAG responde 503. **Nunca vacía**: si no hay clave, omitir la línea (supuesto 1). |
| `KAFKA_SASL_JAAS_CONFIG` | ambos | Con Event Hubs, sí. |
| `REDIS_PASSWORD` | assistant | Con Azure Cache for Redis, sí. |

Para rotar una clave: recrear el Secret (`kubectl delete secret vidacruz-secrets -n vidacruz` y repetir el comando) y luego `kubectl rollout restart deployment -n vidacruz`.

### 4. Aplicar

```bash
kubectl apply -k k8s/
```

### 5. Verificar

```bash
kubectl -n vidacruz rollout status deployment/triage-service
kubectl -n vidacruz rollout status deployment/assistant-service
kubectl -n vidacruz get pods,svc,ingress

# Logs (JSON ECS) y eventos si un pod no llega a Ready
kubectl -n vidacruz logs deployment/triage-service
kubectl -n vidacruz describe pod -l app.kubernetes.io/name=assistant-service

# Probes directo al pod (el Ingress no expone /actuator)
kubectl -n vidacruz port-forward svc/triage-service 8080:8080
curl -s localhost:8080/actuator/health/readiness

# APIs a través del Ingress
IP=$(kubectl -n vidacruz get ingress vidacruz -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
curl -i -H "X-API-Key: $TRIAGE_API_KEY" "http://$IP/triage/pacientes/00000000-0000-0000-0000-000000000000"   # 404
curl -i -H "X-API-Key: $ASSISTANT_API_KEY" "http://$IP/asistente/estado-urgencias"
curl -N -H "X-API-Key: $ASSISTANT_API_KEY" "http://$IP/asistente/alertas/stream"                            # SSE
```

Los ejemplos completos de cada endpoint están en `http/`.

## Servicios gestionados

| Dependencia | Servicio de Azure | ConfigMap | Secret |
|---|---|---|---|
| PostgreSQL | Azure Database for PostgreSQL Flexible Server | `SPRING_R2DBC_URL` (`?sslMode=require`), `SPRING_FLYWAY_URL` (`?sslmode=require`), `POSTGRES_USER` | `POSTGRES_PASSWORD` |
| Kafka | Azure Event Hubs (endpoint Kafka, puerto 9093) | `KAFKA_BOOTSTRAP_SERVERS`, `SPRING_KAFKA_SECURITY_PROTOCOL=SASL_SSL`, `SPRING_KAFKA_PROPERTIES_SASL_MECHANISM=PLAIN` | `KAFKA_SASL_JAAS_CONFIG` |
| Redis | Azure Cache for Redis | `REDIS_HOST`, `REDIS_PORT=6380`, `SPRING_DATA_REDIS_SSL_ENABLED=true` | `REDIS_PASSWORD` |

Las variables `SPRING_*` reemplazan propiedades de `application.yml` por el *relaxed binding* de Spring Boot, así que no hace falta cambiar código ni agregar perfiles:

- **PostgreSQL Flexible Server.** Exige TLS, por eso las URLs completas reemplazan a las que arma el yml con `POSTGRES_HOST`. Crear la base `triage` y el usuario; Flyway crea el esquema al arrancar (con 2 réplicas, Flyway toma un lock en PostgreSQL y solo una aplica las migraciones). Acceso por red privada (VNet integration) o regla de firewall para la salida del clúster.
- **Event Hubs.** Tier Standard o superior (Basic no tiene endpoint Kafka). El namespace equivale al clúster de Kafka y cada event hub a un topic. `triage-service` crea `triaje.eventos` con 3 particiones al arrancar (`NewTopic`, supuesto 11); si la política de la organización no permite crear topics desde la aplicación, crear antes el event hub `triaje.eventos` con 3 particiones. El grupo de consumidores `assistant-service` lo crea el propio consumidor. **Pendiente de verificar** con un namespace real: el factor de replicación (Event Hubs lo gestiona solo) y los timeouts cortos del productor (supuesto 12).
- **Azure Cache for Redis.** TLS en el puerto 6380 con la clave de acceso. Los scripts Lua de la vista (supuesto 24) están soportados. Para una instalación nueva, Microsoft recomienda **Azure Managed Redis**, su sucesor, que se conecta de la misma forma (host, puerto TLS y clave).

En producción las credenciales estáticas se reemplazarían por Workload Identity + Entra ID (PostgreSQL, Event Hubs y Redis lo soportan) o por el CSI driver de Key Vault. Queda como evolución.

## Limitaciones

- No desplegado ni probado en Azure: las conexiones a los servicios gestionados están documentadas pero no verificadas.
- El Ingress no tiene host ni TLS. Para producción: un DNS (`az aks approuting zone add`) y un certificado de Key Vault (anotación `kubernetes.azure.com/tls-cert-keyvault-uri`), o cert-manager.
- `/actuator/prometheus` se sirve en el mismo puerto de la API, protegido con `X-API-Key` (supuesto 33); el Ingress no lo expone. La evolución es un puerto de management separado.
- No hay HorizontalPodAutoscaler ni PodDisruptionBudget: con réplicas fijas no hacen falta para la prueba.

## Validación

Validado sin clúster:

```bash
# Renderiza la kustomization (sintaxis YAML, recursos, namespace, imágenes)
kubectl kustomize k8s/

# Valida los recursos renderizados contra los esquemas de Kubernetes (resultado: 7 válidos, 0 inválidos)
kubectl kustomize k8s/ | docker run --rm -i ghcr.io/yannh/kubeconform:v0.6.7 -strict -summary -
```

Además, la imagen del Asistente se arrancó con las mismas restricciones del pod (`docker run --read-only --tmpfs /tmp --user 100:101 --cap-drop ALL --security-opt no-new-privileges -m 768m`) y la liveness respondió `UP`. No se probó `kubectl apply` contra un clúster.
