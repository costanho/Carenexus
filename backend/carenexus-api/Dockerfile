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

# Copy built application from builder stage
COPY --from=builder /build/target/carenexus-api-*.jar app.jar
RUN chown carenexus:carenexus app.jar
USER carenexus

# Health check against the running app (busybox wget ships with alpine)
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD wget -q -O /dev/null http://localhost:9000/actuator/health || exit 1

# Expose port
EXPOSE 9000

# JWT_SECRET (and the datasource settings) must be provided at runtime; there is no default.
ENTRYPOINT ["java", "-jar", "app.jar"]

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
