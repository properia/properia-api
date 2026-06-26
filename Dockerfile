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

# ffmpeg for virtual tour video concatenation
RUN apk add --no-cache ffmpeg

# Non-root user for security
RUN addgroup -S properia && adduser -S properia -G properia

# Copy the fat jar from build stage
COPY --from=builder /build/target/properia-api-*.jar app.jar

# Change ownership
RUN chown properia:properia app.jar

USER properia

ENTRYPOINT ["java", \
  "-XX:+UseG1GC", \
  "-XX:MaxRAMPercentage=60", \
  "-XX:MaxMetaspaceSize=160m", \
  "-XX:ReservedCodeCacheSize=64m", \
  "-Xss512k", \
  "-Djava.net.preferIPv4Stack=true", \
  "-jar", "app.jar"]

EXPOSE 10000
