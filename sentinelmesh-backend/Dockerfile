# ---- Build stage: compile fat jar with gradle ----
FROM gradle:8.10-jdk21 AS build
WORKDIR /workspace

# Cap heap during the build. With three services building in parallel
# (compose --parallel) the default unbounded JVM is the #1 cause of opaque
# "exit code: 1" failures on 4-8GB hosts. 1.5G is plenty for this project.
ENV GRADLE_OPTS="-Xmx1500m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8"
ENV JAVA_TOOL_OPTIONS="-Xmx1500m"

# Cache dependencies first. We deliberately do NOT swallow errors here:
# dependency-resolution failures (e.g. transient Maven Central / proxy
# issues) used to surface inside the opaque `bootJar` step several minutes
# later; this layer fails fast with a real error message instead.
COPY build.gradle.kts settings.gradle.kts ./
RUN gradle --no-daemon --stacktrace dependencies

# Build the app. --info + --stacktrace makes any compile / annotation-
# processor / classpath failure visible in `docker compose build --progress=plain`.
COPY src ./src
RUN gradle --no-daemon --stacktrace --info clean bootJar -x test

# ---- Runtime stage: small JRE ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S sentinel && adduser -S sentinel -G sentinel
USER sentinel

COPY --from=build /workspace/build/libs/*.jar /app/app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
