# ---- Build stage ----
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
# Resolve dependencies first so they cache independently of source changes
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Tests run in CI (they need Docker for Testcontainers); the image build only packages.
RUN mvn -B -q -DskipTests package

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 kyb
WORKDIR /app
COPY --from=build /src/target/kyb-sanctions-screener-*.jar app.jar
USER kyb
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fs http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
