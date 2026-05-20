# Properia API

Java 21 + Spring Boot 3.4.x backend for the Properia real estate platform.

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 21+ (Eclipse Temurin recommended) |
| Maven | 3.9+ |
| Docker | 24+ |
| Docker Compose | v2 |

### Install Java 21 (macOS)

```bash
# Via SDKMAN (recommended)
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 21-tem
sdk use java 21-tem

# Verify — also works with JDK 26 (compiles to Java 21 target)
java -version
```

### Install Maven

```bash
sdk install maven 3.9.9
mvn -version
```

## Quick Start

```bash
# 1. Copy env template
cp .env.example .env
# Edit .env with real values (DB password, secrets, etc.)

# 2. Start PostgreSQL only (for local dev)
docker compose up postgres -d

# 3. Run the API (Flyway migrations run automatically)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# API is now available at:
# http://localhost:8080/api/health
# http://localhost:8080/swagger-ui.html
```

## Run Everything (API + Frontend + Nginx)

```bash
# From properia-api/ directory
docker compose up --build
# Nginx at http://localhost:80
# Java API at http://localhost:8080
# Next.js at http://localhost:3000
```

## Run Tests

```bash
# All tests (requires Docker for Testcontainers)
./mvnw test

# With coverage report
./mvnw verify
# Report at: target/site/jacoco/index.html
```

## Project Structure

```
src/main/java/pt/properia/api/
├── ProperiaApiApplication.java     # Entry point
├── shared/                         # Shared Kernel (DDD)
│   ├── domain/                     # Base classes, value objects
│   ├── application/                # UseCase<C,R> interface, PageResult
│   └── infrastructure/
│       ├── web/                    # Security, Exception handler, OpenAPI
│       │   └── jwt/               # JWT filter, service, properties
│       └── persistence/            # JPA auditing config
└── modules/                        # Bounded Contexts
    ├── health/                     # Health check
    ├── auth/                       # Authentication (Sprint 1)
    ├── listings/                   # Listings CRUD (Sprint 2)
    ├── search/                     # Search + Geocoding (Sprint 3)
    ├── crm/                        # Leads, Visits, Chat (Sprints 4-5)
    ├── advertiser/                 # Billing, Team, Copilot (Sprints 6-7)
    ├── enrichment/                 # POIs, Zone, Vision (Sprint 8)
    ├── media/                      # File uploads (Sprint 9)
    └── admin/                      # Moderation, Audit (Sprint 9)
```

## Strangler Fig Migration

The Nginx config in `nginx/properia.conf` routes traffic between Next.js and Java.
As each sprint is completed and validated, uncomment the corresponding route block.

Current state (Sprint 0):
- `GET /api/health` → Java ✅
- Everything else → Next.js

## Environment Profiles

| Profile | Use case |
|---------|---------|
| `dev` | Local development (verbose logging, relaxed security) |
| `prod` | Production (strict security, no stack traces) |
| `test` | Integration tests (Testcontainers PostgreSQL) |
