# Multi-stage build for CareNexus Auth Server

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

# Copy built application from builder stage
COPY --from=builder /build/target/server-*.jar app.jar

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD java -cp app.jar org.springframework.boot.loader.JarLauncher \
    -Dmanagement.endpoints.web.exposure.include=health \
    -Dserver.port=9000 || exit 1

# Expose port
EXPOSE 9000

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
