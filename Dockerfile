# syntax=docker/dockerfile:1

# ---- Estagio 1: build (JDK + Maven wrapper) ----
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Camada de dependencias separada: so e refeita quando o pom.xml muda.
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q -DskipTests package

# ---- Estagio 2: runtime enxuto (somente JRE, usuario sem privilegios) ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app -H -s /sbin/nologin app
WORKDIR /app
COPY --from=build /workspace/target/deroyque.jar app.jar
USER app

# Memoria da JVM proporcional ao limite do contêiner (docker run -m / resources.limits).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError" \
    SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -q -O /dev/null http://localhost:8080/actuator/health/liveness || exit 1

# Desligamento gracioso: o SIGTERM do `docker stop` aciona server.shutdown=graceful.
ENTRYPOINT ["java", "-jar", "app.jar"]
