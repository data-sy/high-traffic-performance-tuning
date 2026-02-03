# Project: High Traffic Performance Tuning

## Overview
E-commerce backend performance optimization portfolio project.
Demonstrates measurable before/after improvements across 4 scenarios.

## Scenarios
1. **Index Optimization** — Product list query with composite index (category, created_at)
2. **N+1 Resolution** — Order detail query with Fetch Join
3. **Real-time Ranking** — Redis Sorted Set vs DB ORDER BY
4. **Concurrency Control** — Coupon issuance with distributed lock

## Tech Stack
- Java 17, Spring Boot 3.x, Gradle (Groovy DSL)
- MySQL 8.0, Redis 7.x
- k6 (load testing), Docker Compose

## Specs
- Phase-by-phase specs are in `specs/` directory
- Follow each spec exactly as written
- Do not deviate from the spec unless explicitly asked

## Architecture
- Monolithic single-schema design
- Layered: `domain/` → `service/` → `api/`
- Infrastructure concerns in `infrastructure/`

## Entity Rules
- `@GeneratedValue(strategy = GenerationType.IDENTITY)` for all PKs
- `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, `@Getter` on all entities
- No Setters. Use business methods instead
- `@CreatedDate` + `@Column(updatable = false)` on all createdAt fields
- `@EntityListeners(AuditingEntityListener.class)` on all entities

## Relationship Rules (CRITICAL)
- **Order → User**: NO relationship. `userId(Long)` only. Intentional.
- **Order → OrderItem**: `@OneToMany(mappedBy = "order")`
- **OrderItem → Order**: `@ManyToOne(fetch = LAZY)`
- **OrderItem → Product**: `@ManyToOne(fetch = LAZY)` — REQUIRED for N+1 scenario
- **CouponIssue → Coupon, User**: NO relationship. `couponId(Long)`, `userId(Long)` only.
- Do NOT add any relationships not listed above.

## API Structure
Each scenario has v1~v4 endpoints representing each optimization attempt:
- `v1` = baseline (no optimization)
- `v2` = first attempt
- `v3` = second attempt
- `v4` = final solution
- All versions coexist in the codebase for independent comparison and re-testing.
- Example (Index): v1=no index, v2=category index, v3=created_at index, v4=composite index
- Example (N+1): v1=LAZY default, v2=EAGER, v3=@BatchSize, v4=Fetch Join
- Example (Ranking): v1=DB ORDER BY, v2=DB index, v3=Redis String, v4=Redis Sorted Set
- Example (Concurrency): v1=no lock, v2=synchronized, v3=SELECT FOR UPDATE, v4=Redis lock

## Logging
- No P6Spy (Spring Boot 3.x incompatible)
- Use Hibernate native logging:
  - `org.hibernate.SQL: DEBUG`
  - `org.hibernate.orm.jdbc.bind: TRACE`

## Docker Usage
- `docker compose -f docker/docker-compose.yml up -d` starts containers
- `docker compose -f docker/docker-compose.yml down` stops containers but keeps data (volumes preserved)
- `docker compose -f docker/docker-compose.yml down -v` deletes volumes — ALL DATA IS LOST
- After `-v` (full reset): must re-run the app (`ddl-auto: create`) to recreate tables, then `scripts/init-seed-data.sh` and/or `scripts/generate-test-data.sh` to reload data
- Never use `-v` unless intentionally doing a full reset

## Conventions
- Package structure: `com.project.{layer}.{domain}`
- Response DTOs in `api/{domain}/dto/`. Never expose entities directly.
- Korean category names: 전자기기, 의류, 식품, 도서, 스포츠
