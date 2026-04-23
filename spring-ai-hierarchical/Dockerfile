FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
	mvn -B -DskipTests dependency:resolve dependency:resolve-plugins
COPY src src
RUN --mount=type=cache,target=/root/.m2 \
	mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /workspace/target/*.jar /app/app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=100", "-jar", "/app/app.jar"]