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

# Stage 2: Runtime
FROM amazoncorretto:17-alpine
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
