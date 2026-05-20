# ── Stage 1: Build ───────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Cache dependencies before copying source (layer caching optimization)
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and build (skip tests in Docker — run them in CI)
COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Non-root user for security
RUN addgroup -S properia && adduser -S properia -G properia

# Copy the fat jar from build stage
COPY --from=builder /build/target/properia-api-*.jar app.jar

# Change ownership
RUN chown properia:properia app.jar

USER properia

ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-XX:MaxRAMPercentage=75", \
  "-jar", "app.jar"]

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/health || exit 1
