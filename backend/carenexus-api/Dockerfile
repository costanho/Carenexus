# Multi-stage build for the CareNexus API

# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# Copy pom.xml and download dependencies
COPY pom.xml .
RUN mvn dependency:go-offline

# Copy source code and build
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime (the default image: starts and runs as the unprivileged user)
FROM amazoncorretto:17-alpine AS runtime
WORKDIR /app

# Run as an unprivileged user
RUN addgroup -S carenexus && adduser -S carenexus -G carenexus

# The jar is extracted (app.jar + lib/) and a class data sharing (CDS) archive is recorded from a training run that
# refreshes the Spring context and exits. Loading classes from app.jsa instead of the jars roughly halves startup on a
# small or throttled CPU. The training run reaches no database (Flyway off, schema validation off, no JDBC metadata,
# an unreachable URL) and its JWT and MFA keys are random values generated here and thrown away; nothing from it ends
# up in the image except app.jsa. A JVM that cannot use the archive starts without it.
COPY --from=builder /build/target/carenexus-api-*.jar /tmp/app.jar
RUN java -Djarmode=tools -jar /tmp/app.jar extract --destination /app/extracted \
    && mv /app/extracted/app.jar /app/extracted/lib /app/ && rmdir /app/extracted && rm /tmp/app.jar \
    && if ! JWT_SECRET="$(head -c 64 /dev/urandom | base64 | tr -d '\n')" \
            AUTH_MFA_ENCRYPTION_KEY="$(head -c 32 /dev/urandom | base64 | tr -d '\n')" \
          java -XX:ArchiveClassesAtExit=/app/app.jsa -Xlog:cds=off -Xlog:cds+dynamic=off \
          -Dspring.context.exit=onRefresh \
          -Dspring.flyway.enabled=false -Dspring.jpa.hibernate.ddl-auto=none \
          -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
          -Dspring.datasource.url=jdbc:postgresql://127.0.0.1:1/cds-training \
          -Dspring.datasource.username=cds -Dspring.datasource.password=cds \
          -Dcarenexus.environment=development \
          -jar /app/app.jar > /tmp/cds-training.log 2>&1; then \
          grep -E "APPLICATION FAILED|Error creating bean with name|Caused by" /tmp/cds-training.log | cut -c1-300 | head -20; exit 1; fi \
    && test -s /app/app.jsa && rm -f /tmp/cds-training.log
USER carenexus

# Health check against the running app (busybox wget ships with alpine)
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD wget -q -O /dev/null http://localhost:9000/actuator/health || exit 1

# Expose port
EXPOSE 9000

# JWT_SECRET (and the datasource settings) must be provided at runtime; there is no default.
ENTRYPOINT ["java", "-XX:SharedArchiveFile=/app/app.jsa", "-Xlog:cds=off", "-jar", "/app/app.jar"]

# Optional target `volume-owner` (docker build --target volume-owner): for hosts that mount a root-owned volume at
# STORAGE_LOCAL_DIR (Fly.io volumes, many Kubernetes volume types). It starts as root only to create that directory
# and hand it to the carenexus user, then runs the API as carenexus (su-exec). Same jar, same health check.
FROM runtime AS volume-owner
USER root
RUN apk add --no-cache su-exec
COPY docker/volume-entrypoint.sh /usr/local/bin/volume-entrypoint.sh
RUN chmod 0755 /usr/local/bin/volume-entrypoint.sh
ENTRYPOINT ["/usr/local/bin/volume-entrypoint.sh"]

# Default target: the unprivileged runtime image above, unchanged.
FROM runtime
