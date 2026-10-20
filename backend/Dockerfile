# syntax=docker/dockerfile:1.7
# Build from the repository root:  docker build -f backend/Dockerfile --target api .
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /src
COPY backend/ ./
RUN gradle --no-daemon :api:installDist :etl:installDist -x test

FROM eclipse-temurin:21-jre-alpine AS runtime-base
# OpenTelemetry Java agent: traces, metrics and logs over OTLP to the LGTM stack (Alloy/Tempo/Mimir/Loki)
ADD --chmod=644 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.15.0/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -javaagent:/otel/opentelemetry-javaagent.jar" \
    OTEL_SERVICE_NAME=care-ml-workbench-api \
    OTEL_LOGS_EXPORTER=otlp \
    OTEL_INSTRUMENTATION_HTTP_SERVER_CAPTURE_REQUEST_HEADERS="" \
    OTEL_EXPERIMENTAL_RESOURCE_DISABLED_PROVIDERS=""

# --- API (production image) -----------------------------------------------------------------
FROM runtime-base AS api
COPY --from=build /src/api/build/install/api /app
USER 10001
EXPOSE 8080
ENTRYPOINT ["/app/bin/api"]

# --- ETL (CronJob image) --------------------------------------------------------------------
FROM runtime-base AS etl
ENV OTEL_SERVICE_NAME=care-ml-workbench-etl
COPY --from=build /src/etl/build/install/etl /app
USER 10001
ENTRYPOINT ["/app/bin/etl"]

# --- API for docker compose: trains in-process with the Python trainer (TRAINER_MODE=local) ---
# Debian-based so the scientific Python wheels install without compiling.
FROM eclipse-temurin:21-jre AS dev
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip wget && rm -rf /var/lib/apt/lists/* && \
    useradd --uid 10001 --create-home app
COPY trainer/requirements.txt /tmp/requirements.txt
RUN python3 -m pip install --no-cache-dir --break-system-packages -r /tmp/requirements.txt
COPY --from=runtime-base /otel /otel
COPY --from=build /src/api/build/install/api /app
COPY trainer/ /work/trainer/
RUN mkdir -p /work/build && chown -R 10001:10001 /work
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -javaagent:/otel/opentelemetry-javaagent.jar" \
    OTEL_SERVICE_NAME=care-ml-workbench-api \
    OTEL_LOGS_EXPORTER=otlp
WORKDIR /work
USER 10001
EXPOSE 8080
ENTRYPOINT ["/app/bin/api"]
