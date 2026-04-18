FROM maven:3.9.5-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy pom.xml and download dependencies
COPY pom.xml .
RUN mvn dependency:resolve

# Copy source code and build
COPY src src/
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy JAR from builder
COPY --from=builder /build/target/spring-ai-faq-ingestion-0.0.1-SNAPSHOT.jar app.jar

# Create directories for data persistence
RUN mkdir -p /app/uploads /app/chroma_data /app/data

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD java -cp app.jar org.springframework.boot.loader.JarLauncher ping || exit 1

# Environment variables
ENV JAVA_OPTS="-Xmx512m -Xms256m"
ENV SPRING_PROFILES_ACTIVE="prod"

EXPOSE 9000

ENTRYPOINT ["java", "-jar", "app.jar"]
