# syntax=docker/dockerfile:1

# ---- Build stage: compile and produce a Spring Boot jar ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Warm the Gradle dependency cache separately from the sources for better layer caching.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies || true

COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon clean bootJar \
    && cp build/libs/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- Runtime stage: minimal JRE, non-root, layered for cache-friendly deploys ----
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /application

# curl is the only addition to the base image: the compose healthcheck and every "is it up?" check
# on the server go through it, and neither curl nor wget ships in eclipse-temurin.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

# Every timestamp the app prints — logs, the demo call log — is read next to a Telegram chat that shows
# Kyiv time. A UTC container makes every line three hours off from the conversation being debugged.
# Business logic never relies on this: the Clock bean and every date calculation name Europe/Kyiv explicitly.
ENV TZ=Europe/Kyiv

RUN groupadd --system spring && useradd --system --gid spring spring

# logback's DEMO_FILE appender (task 58) writes logs/mcp-calls.log relative to this WORKDIR, and task 55's
# pitch artifact reads it. The directory has to exist and belong to `spring` BEFORE the user switch —
# a root-owned WORKDIR makes the appender fail at boot with a 40-line stack trace and no call log at all.
RUN mkdir -p /application/logs && chown -R spring:spring /application

USER spring:spring

COPY --from=build --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/application/ ./

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
