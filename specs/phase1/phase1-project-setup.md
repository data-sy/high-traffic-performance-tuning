# Phase 1 — 프로젝트 기반 세팅

## 목표
Spring Boot 프로젝트를 초기화하고, 로컬 개발 환경(MySQL, Redis)을 Docker로 구성하여 빈 프로젝트가 정상 기동되는 상태를 만든다.

## 기술 스택
- Java 17
- Spring Boot 3.x (최신 안정 버전)
- Gradle (Groovy DSL)
- MySQL 8.0
- Redis 7.x

## 작업 항목

### 1. Spring Boot 프로젝트 초기화
Spring Initializr API를 사용하여 프로젝트를 생성한다.

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

### 2. build.gradle 의존성
Initializr에서 생성된 기본 의존성만 사용한다.
- P6Spy는 사용하지 않는다. Spring Boot 3.x 호환성 문제가 있으므로 Hibernate 네이티브 로깅으로 대체한다.

### 3. 디렉토리 구조
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
빈 패키지에 `.gitkeep` 또는 `package-info.java`를 두어 디렉토리가 유지되도록 한다.

### 4. docker-compose.yml
`docker/docker-compose.yml` 위치에 작성한다.

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
- `TZ: Asia/Seoul`로 시간대를 설정하여 LocalDateTime과의 시간 불일치를 방지한다.
- `mysql-data` 볼륨으로 컨테이너 재시작 시 데이터 유실을 방지한다.
- Prometheus, Grafana는 Phase 4에서 추가한다.

### 5. init-data.sql 사전 생성
Docker Compose 실행 전에 빈 파일이라도 반드시 존재해야 한다.
파일이 없으면 Docker가 디렉토리로 생성하여 MySQL 초기화가 실패한다.

`docker/init-data.sql`:
```sql
-- 테스트 데이터는 Phase 2에서 추가
```

이 파일은 Phase 1 작업의 첫 단계에서 생성한다.

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
- P6Spy 대신 Hibernate 네이티브 로깅으로 쿼리와 바인딩 파라미터를 확인한다.
- `show-sql: true`는 사용하지 않는다. `logging.level`로 통일한다.
- `serverTimezone=Asia/Seoul`을 JDBC URL에 명시한다.
- `ddl-auto: create`는 개발 단계에서만 사용. Phase 2 엔티티 작업 후 `validate`로 변경한다.

## 완료 조건
- [ ] `docker/init-data.sql` 빈 파일 생성 확인
- [ ] `docker compose up -d`로 MySQL, Redis 정상 기동
- [ ] Spring Boot 애플리케이션 정상 실행 (`./gradlew bootRun`)
- [ ] Hibernate 쿼리 로그 출력 확인
- [ ] 에러 없이 기동 로그 확인
