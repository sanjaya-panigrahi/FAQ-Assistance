FROM maven:3.9.5-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy pom.xml and download dependencies
COPY pom.xml .
RUN mvn dependency:resolve

# Copy source code and build
COPY src src/
RUN mvn clean package -DskipTests

# Runtime stage — alpine JRE is ~95 MB lighter than the Debian variant
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# curl is needed for the health check; add in one layer and keep the cache clean
RUN apk add --no-cache curl

# Copy JAR from builder
COPY --from=builder /build/target/spring-ai-faq-ingestion-0.0.1-SNAPSHOT.jar app.jar

# Create directories for data persistence
RUN mkdir -p /app/uploads /app/chroma_data /app/data

# Run as non-root for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup \
    && chown -R appuser:appgroup /app
USER appuser

# Health check — uses curl against the actuator/management endpoint
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:9000/api/faq-ingestion/health || exit 1

ENV SPRING_PROFILES_ACTIVE="prod"

EXPOSE 9000

# -XX:+UseContainerSupport (default JDK 11+, stated explicitly for clarity)
# -XX:MaxRAMPercentage=75 caps heap at 75 % of the container memory limit
# -XX:+UseG1GC gives better throughput than SerialGC for a concurrent web service
ENTRYPOINT ["java", \
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75", \
            "-XX:+UseG1GC", \
            "-XX:MaxGCPauseMillis=100", \
            "-jar", "/app/app.jar"]
