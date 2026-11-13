# syntax=docker/dockerfile:1
# Triage service image. Build context: the repository root (the module needs the parent pom), e.g.
#   docker build -f triage-service/Dockerfile .
# Tests are not run here (they need Docker for Testcontainers): run ./mvnw verify before building.

# --- build: compile only this module with the Maven Wrapper of the root ---------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Wrapper and every pom first: the reactor reads all module poms even with -pl
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
COPY triage-service/pom.xml triage-service/
COPY assistant-service/pom.xml assistant-service/
COPY triage-service/src/ triage-service/src/

# The cache mount keeps ~/.m2 (and the Maven distribution downloaded by the wrapper) between builds.
# maven.test.skip: tests are neither compiled nor run in the image build.
RUN --mount=type=cache,target=/root/.m2 \
    sh ./mvnw -B -q -pl triage-service -am -Dmaven.test.skip=true package \
 && cp triage-service/target/triage-service-*.jar application.jar \
 && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# --- runtime: minimal JRE, non-root user, one image layer per Spring Boot layer ------------------------------------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /application

# From least to most frequently changed: a code change only rebuilds the last layer
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app
EXPOSE 8080

# Heap sized from the container memory limit; exit (and let the orchestrator restart) on OutOfMemoryError
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Public readiness probe; busybox wget exits non-zero on 503 (DOWN) or no answer
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=10 \
    CMD wget -q -O /dev/null http://localhost:8080/actuator/health/readiness || exit 1

# Exec form: SIGTERM reaches the JVM, so Spring Boot shuts down gracefully
ENTRYPOINT ["java", "-jar", "application.jar"]
