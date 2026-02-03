# Phase 2 — 도메인 엔티티 + 테이블 생성

## 목표
ERD를 기반으로 JPA 엔티티와 Repository를 생성하고, 테스트 데이터를 초기화하여 다음 Phase에서 바로 API를 구현할 수 있는 상태를 만든다.

## 설계 원칙

### 연관관계 전략
- **Order → User**: 연관관계를 맺지 않고 `userId(Long)`으로만 관리한다. 조회 성능 및 단순화를 위한 의도적인 비연관 설계이다. Claude Code는 User 엔티티에 대한 `@ManyToOne`을 추가하지 않는다.
- **Order → OrderItem**: `@OneToMany` 연관관계를 맺는다. N+1 시나리오 재현에 필요하다.
- **OrderItem → Product**: `@ManyToOne(fetch = LAZY)` 연관관계를 맺는다. N+1 시나리오에서 LAZY 로딩으로 문제를 재현하고, Fetch Join으로 해결하기 위해 반드시 객체 연관관계가 필요하다.
- **CouponIssue → Coupon, User**: 연관관계를 맺지 않고 `couponId(Long)`, `userId(Long)`으로만 관리한다.

### ID 생성 전략
- 모든 엔티티의 PK는 `@GeneratedValue(strategy = GenerationType.IDENTITY)`를 사용한다.
- MySQL의 AUTO_INCREMENT와 매핑되며, 다른 전략(TABLE, SEQUENCE)은 사용하지 않는다.

### before/after API 분리 구조
Phase 3에서 각 시나리오는 최적화 전/후를 별도 API로 분리하여 동시에 비교 가능하게 한다.
예시:
- `GET /api/v1/products` — 인덱스 없이 조회 (before)
- `GET /api/v2/products` — 복합 인덱스 적용 조회 (after)

Phase 2에서는 엔티티와 Repository만 생성하고, API는 Phase 3에서 구현한다.
엔티티 설계 시 before/after 양쪽에서 모두 사용 가능하도록 범용적으로 설계한다.

## ERD
```
┌──────────┐      ┌─────────────┐      ┌──────────┐
│   User   │      │ CouponIssue │      │  Coupon  │
├──────────┤      ├─────────────┤      ├──────────┤
│ id (PK)  │◀────▶│ id (PK)     │◀────▶│ id (PK)  │
│ email    │ 1:N  │ coupon_id   │ N:1  │ name     │
│ name     │      │ user_id     │      │ total_qty│
│created_at│      │ issued_at   │      │ issued   │
└────┬─────┘      └─────────────┘      │created_at│
     │ 1:N                             └──────────┘
     ▼
┌──────────┐      ┌───────────┐      ┌──────────┐
│  Order   │      │ OrderItem │      │ Product  │
├──────────┤      ├───────────┤      ├──────────┤
│ id (PK)  │◀────▶│ id (PK)   │◀────▶│ id (PK)  │
│ user_id  │ 1:N  │ order_id  │ N:1  │ name     │
│ status   │      │ product_id│      │ price    │
│ total    │      │ quantity  │      │ category │
│created_at│      │ price     │      │ stock    │
└──────────┘      └───────────┘      │ sales_cnt│
                                     │created_at│
                                     └──────────┘
```

## 작업 항목

### 1. User 엔티티
- 위치: `domain/user/User.java`
- 필드:
  - `id` — Long, PK, `@GeneratedValue(strategy = GenerationType.IDENTITY)`
  - `email` — String, `@Column(unique = true)`
  - `name` — String
  - `createdAt` — LocalDateTime, `@CreatedDate`, `@Column(updatable = false)`
- `@Entity`, `@Table(name = "users")`

### 2. User Repository
- 위치: `domain/user/UserRepository.java`
- `JpaRepository<User, Long>`

### 3. Product 엔티티
- 위치: `domain/product/Product.java`
- 필드:
  - `id` — Long, PK, `@GeneratedValue(strategy = GenerationType.IDENTITY)`
  - `name` — String
  - `price` — Integer
  - `category` — String
  - `stock` — Integer
  - `salesCount` — Integer, 기본값 0, `@Column(name = "sales_cnt")`
  - `createdAt` — LocalDateTime, `@CreatedDate`, `@Column(updatable = false)`
- salesCount는 주문 발생 시 갱신이 필요하나, 그 로직은 Phase 3-3에서 구현한다. Phase 2에서는 필드 정의와 초기 데이터 세팅만 한다.

### 4. Product Repository
- 위치: `domain/product/ProductRepository.java`
- `JpaRepository<Product, Long>`

### 5. Order 엔티티
- 위치: `domain/order/Order.java`
- 필드:
  - `id` — Long, PK, `@GeneratedValue(strategy = GenerationType.IDENTITY)`
  - `userId` — Long (User와 연관관계를 맺지 않는다. 의도적인 비연관 설계)
  - `status` — OrderStatus enum, `@Enumerated(EnumType.STRING)`
  - `total` — Integer
  - `createdAt` — LocalDateTime, `@CreatedDate`, `@Column(updatable = false)`
- OrderStatus enum: `PENDING`, `PAID`, `PREPARING`, `SHIPPED`, `DELIVERED`, `CANCELLED`, `REFUNDED`
- OrderItem과의 연관관계: `@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)`

### 6. OrderItem 엔티티
- 위치: `domain/order/OrderItem.java`
- 필드:
  - `id` — Long, PK, `@GeneratedValue(strategy = GenerationType.IDENTITY)`
  - `order` — Order, `@ManyToOne(fetch = FetchType.LAZY)`, `@JoinColumn(name = "order_id")`
  - `product` — Product, `@ManyToOne(fetch = FetchType.LAZY)`, `@JoinColumn(name = "product_id")`
  - `quantity` — Integer
  - `price` — Integer
- **중요**: Product와 반드시 객체 연관관계(`@ManyToOne`)를 맺어야 한다. Phase 3-2에서 LAZY 로딩으로 N+1 문제를 재현하고, Fetch Join으로 해결하기 위해 필수이다.

### 7. Coupon 엔티티
- 위치: `domain/coupon/Coupon.java`
- 필드:
  - `id` — Long, PK, `@GeneratedValue(strategy = GenerationType.IDENTITY)`
  - `name` — String
  - `totalQuantity` — Integer, `@Column(name = "total_qty")`
  - `issued` — Integer, 기본값 0
  - `createdAt` — LocalDateTime, `@CreatedDate`, `@Column(updatable = false)`

### 8. CouponIssue 엔티티
- 위치: `domain/coupon/CouponIssue.java`
- 필드:
  - `id` — Long, PK, `@GeneratedValue(strategy = GenerationType.IDENTITY)`
  - `couponId` — Long (Coupon과 연관관계를 맺지 않는다)
  - `userId` — Long (User와 연관관계를 맺지 않는다)
  - `issuedAt` — LocalDateTime
- 중복 발급 방지: `@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"coupon_id", "user_id"}))`

### 9. Coupon Repository
- 위치: `domain/coupon/CouponRepository.java`
- `JpaRepository<Coupon, Long>`

### 10. CouponIssue Repository
- 위치: `domain/coupon/CouponIssueRepository.java`
- `JpaRepository<CouponIssue, Long>`

### 11. 테스트 데이터 초기화 (init-data.sql)
`docker/init-data.sql`에 기본 구조 확인용 소량 데이터만 넣는다.

```sql
-- User: 10명
-- Product: 100개 (category: 전자기기, 의류, 식품, 도서, 스포츠 중 랜덤)
-- Order: 50건 (랜덤 user_id, status: PAID 또는 DELIVERED)
-- OrderItem: Order당 1~3개 (랜덤 product_id)
-- Coupon: 3개 (각 total_qty: 100, issued: 0)
-- CouponIssue: 비워둠 (Phase 3-4에서 동시성 테스트용)
```

### 12. 대용량 데이터 생성 스크립트
- 위치: `scripts/generate-test-data.sh`
- MySQL CLI를 사용하여 아래 규모의 데이터를 INSERT한다:
  - User: 1,000명
  - Product: 100,000개 (category 5종 균등 분배)
  - Order: 50,000건
  - OrderItem: Order당 1~5개
- Product의 sales_cnt는 해당 product_id에 연결된 OrderItem의 quantity 합계로 UPDATE한다.
- Coupon의 issued는 0으로 유지한다 (Phase 3-4에서 동시성 테스트용).

## 주의사항
- 모든 엔티티에 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, `@Getter` 적용
- Setter는 사용하지 않는다. 필요한 경우 비즈니스 메서드로 대체한다.
- `@EntityListeners(AuditingEntityListener.class)` 모든 엔티티에 적용
- Application 클래스에 `@EnableJpaAuditing` 추가
- Phase 2 완료 후 `ddl-auto`를 `create`에서 `validate`로 변경한다.

## 완료 조건
- [ ] `./gradlew bootRun` 시 테이블 자동 생성 확인
- [ ] Hibernate 로그에서 CREATE TABLE 쿼리 확인
- [ ] init-data.sql로 기본 데이터 입력 확인
- [ ] generate-test-data.sh 실행 시 대용량 데이터 생성 확인
- [ ] Product 10만 건, Order 5만 건 데이터 수 확인
- [ ] ddl-auto를 `validate`로 변경 후 정상 기동 확인
- [ ] OrderItem → Product 연관관계 매핑 정상 확인 (LAZY 로딩)
