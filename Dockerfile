# syntax=docker/dockerfile:1.7
# Imagen única de Climind SaaS: API (Spring Boot) + frontend estático.
# Contexto de build: la raíz del repositorio (necesita backend/ y frontend/).

# ---- Build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml backend/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -f backend/pom.xml dependency:go-offline
COPY backend/src backend/src
# El frontend se empaqueta dentro del jar (classpath:/static/). Solo lo que se sirve: nada de tests ni node_modules.
COPY frontend/cotiza backend/src/main/resources/static/cotiza
COPY frontend/saas   backend/src/main/resources/static/saas
COPY frontend/shared backend/src/main/resources/static/shared
COPY frontend/vendor backend/src/main/resources/static/vendor
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -f backend/pom.xml -Dmaven.test.skip=true package

# ---- Runtime ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /app/storage && chown app:app /app/storage
COPY --from=build /src/backend/target/climind-saas.jar app.jar
USER app
EXPOSE 8080
VOLUME ["/app/storage"]
ENV SPRING_PROFILES_ACTIVE=prod \
    TZ=UTC \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# El health check usa el mismo puerto que la app (EasyPanel puede definir PORT por su cuenta).
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -qO- "http://localhost:${PORT:-8080}/actuator/health" || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
