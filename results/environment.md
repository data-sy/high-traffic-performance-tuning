# Measurement Environment

## Machine Specifications
- **OS**: macOS (Darwin)
- **CPU**: Apple M2
- **Memory**: 16GB
- **Disk**: SSD

## Software Versions
- **Java**: OpenJDK 17.0.17 (Homebrew)
- **Spring Boot**: 3.5.10
- **k6**: 1.5.0 (darwin/arm64)

## Docker Environment
- **Docker**: 29.1.5
- **MySQL**: 8.0.45 (Docker container: docker-mysql-1, default settings)
- **Redis**: 7.x (Docker container: docker-redis-1)

## Test Configuration
- **k6 VUs**: 10
- **k6 Duration**: 30s
- **Target Endpoint**: `GET /api/v1/products?category=전자기기&page=0&size=20`
- **Product Table Rows**: 100,000 (20,000 per category)

## Notes
- All measurements performed on local development machine
- Docker containers running via `docker compose up -d`
- Spring Boot application running via `./gradlew bootRun`
