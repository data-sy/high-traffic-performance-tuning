# Phase 1 — Project Setup

## Goal
Initialize Spring Boot project with Docker-based local dev environment (MySQL, Redis). End state: empty project boots successfully.

## Tech Stack
- Java 17, Spring Boot 3.x (latest stable), Gradle (Groovy DSL)
- MySQL 8.0, Redis 7.x

## Tasks

### 1. Project Init
Use Spring Initializr API:
```bash
curl https://start.spring.io/starter.zip \
  -d type=gradle-project \
  -d language=java \
  -d javaVersion=17 \
  -d bootVersion=3.4.1 \
  -d groupId=com.project \
  -d artifactId=high-traffic-performance-tuning \
  -d name=high-traffic-performance-tuning \
  -d dependencies=web,data-jpa,data-redis,mysql,validation,lombok \
  -o project.zip
```

### 2. Dependencies
Use only Initializr defaults. Do NOT add P6Spy (incompatible with Spring Boot 3.x). Use Hibernate native logging instead.

### 3. Directory Structure
```
src/main/java/com/project/
├── domain/
│   ├── user/
│   ├── product/
│   ├── order/
│   └── coupon/
├── api/
│   ├── product/
│   ├── order/
│   └── coupon/
├── service/
│   ├── product/
│   ├── order/
│   └── coupon/
└── infrastructure/
    ├── redis/
    └── lock/
```
Add `.gitkeep` or `package-info.java` to empty packages.

### 4. docker-compose.yml
Location: `docker/docker-compose.yml`
```yaml
services:
  mysql:
    image: mysql:8.0
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: flashdeal
      TZ: Asia/Seoul
    volumes:
      - mysql-data:/var/lib/mysql
      - ./init-data.sql:/docker-entrypoint-initdb.d/init-data.sql

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

volumes:
  mysql-data:
```
- `TZ: Asia/Seoul` prevents LocalDateTime timezone mismatch
- `mysql-data` volume prevents data loss on container restart
- Prometheus/Grafana added in Phase 4

### 5. init-data.sql
CRITICAL: This file MUST exist before `docker compose up`. If missing, Docker creates a directory instead, causing MySQL init failure.

Create `docker/init-data.sql` as first step:
```sql
-- Test data added in Phase 2
```

### 6. application.yml
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/flashdeal?serverTimezone=Asia/Seoul
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: create
    properties:
      hibernate:
        format_sql: true
  data:
    redis:
      host: localhost
      port: 6379

logging:
  level:
    org.hibernate.SQL: DEBUG
    org.hibernate.orm.jdbc.bind: TRACE
```
- Hibernate native logging replaces P6Spy
- Do NOT use `show-sql: true` (use `logging.level` instead)
- Change `ddl-auto` to `validate` after Phase 2

## Done Criteria
- [ ] `docker/init-data.sql` exists
- [ ] `docker compose up -d` starts MySQL + Redis
- [ ] `./gradlew bootRun` succeeds
- [ ] Hibernate query logs visible
- [ ] No errors in startup log
