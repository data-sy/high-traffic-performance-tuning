# Phase 2 — Domain Entities + Tables

## Goal
Create JPA entities and repositories based on ERD. Seed test data. End state: ready for API implementation in Phase 3.

## Design Principles

### Relationship Strategy
- **Order → User**: NO relationship. Use `userId(Long)` only. Intentional denormalized design for simplicity. Do NOT add `@ManyToOne` to User.
- **Order → OrderItem**: `@OneToMany` relationship. Required for N+1 scenario.
- **OrderItem → Product**: `@ManyToOne(fetch = LAZY)` relationship. REQUIRED for N+1 reproduction and Fetch Join resolution in Phase 3-2.
- **CouponIssue → Coupon, User**: NO relationship. Use `couponId(Long)`, `userId(Long)` only.

### ID Strategy
All entities: `@GeneratedValue(strategy = GenerationType.IDENTITY)`. Maps to MySQL AUTO_INCREMENT. Do NOT use TABLE or SEQUENCE.

### Phase 3 API Structure
Phase 3 implements APIs for each scenario. API structure varies by scenario:
- **Index Optimization (Phase 3-1)**: Single API. Query is identical — only the DB index changes. Index switching is controlled by SQL scripts, NOT separate controllers.
- **N+1, Concurrency, etc.**: Decided per scenario.

Phase 2 only creates entities and repositories. APIs come in Phase 3.

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

## Tasks

### 1. User
- Location: `domain/user/User.java`
- Fields: `id`(Long, PK, IDENTITY), `email`(String, unique), `name`(String), `createdAt`(LocalDateTime, @CreatedDate, updatable=false)
- `@Table(name = "users")`

### 2. UserRepository
- Location: `domain/user/UserRepository.java`
- `JpaRepository<User, Long>`

### 3. Product
- Location: `domain/product/Product.java`
- Fields: `id`(Long, PK, IDENTITY), `name`(String), `price`(Integer), `category`(String), `stock`(Integer), `salesCount`(Integer, default 0, `@Column(name = "sales_cnt")`), `createdAt`(LocalDateTime, @CreatedDate, updatable=false)
- salesCount update logic is implemented in Phase 3-3. Phase 2 only defines the field and seeds initial data.

### 4. ProductRepository
- Location: `domain/product/ProductRepository.java`
- `JpaRepository<Product, Long>`

### 5. Order
- Location: `domain/order/Order.java`
- Fields: `id`(Long, PK, IDENTITY), `userId`(Long, NOT a relationship), `status`(OrderStatus enum, `@Enumerated(EnumType.STRING)`), `total`(Integer), `createdAt`(LocalDateTime, @CreatedDate, updatable=false)
- OrderStatus: `PENDING`, `PAID`, `PREPARING`, `SHIPPED`, `DELIVERED`, `CANCELLED`, `REFUNDED`
- `@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)` to OrderItem

### 6. OrderItem
- Location: `domain/order/OrderItem.java`
- Fields: `id`(Long, PK, IDENTITY), `quantity`(Integer), `price`(Integer)
- Relationships:
  - `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "order_id") private Order order`
  - `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "product_id") private Product product`
- CRITICAL: Product relationship (`@ManyToOne`) is REQUIRED. Without it, Fetch Join in Phase 3-2 is impossible.

### 7. Coupon
- Location: `domain/coupon/Coupon.java`
- Fields: `id`(Long, PK, IDENTITY), `name`(String), `totalQuantity`(Integer, `@Column(name = "total_qty")`), `issued`(Integer, default 0), `createdAt`(LocalDateTime, @CreatedDate, updatable=false)

### 8. CouponIssue
- Location: `domain/coupon/CouponIssue.java`
- Fields: `id`(Long, PK, IDENTITY), `couponId`(Long), `userId`(Long), `issuedAt`(LocalDateTime)
- `@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"coupon_id", "user_id"}))`

### 9. CouponRepository
- Location: `domain/coupon/CouponRepository.java`
- `JpaRepository<Coupon, Long>`

### 10. CouponIssueRepository
- Location: `domain/coupon/CouponIssueRepository.java`
- `JpaRepository<CouponIssue, Long>`

### 11. Seed Data (init-data.sql)
Location: `docker/init-data.sql`. Small dataset for structure validation:
- User: 10
- Product: 100 (categories: 전자기기, 의류, 식품, 도서, 스포츠, random)
- Order: 50 (random user_id, status: PAID or DELIVERED)
- OrderItem: 1~3 per Order (random product_id)
- Coupon: 3 (total_qty: 100 each, issued: 0)
- CouponIssue: empty (reserved for Phase 3-4 concurrency test)

### 12. Bulk Data Script
- Location: `scripts/generate-test-data.sh`
- Uses MySQL CLI to INSERT:
  - User: 1,000
  - Product: 100,000 (5 categories, evenly distributed)
  - Order: 50,000
  - OrderItem: 1~5 per Order
- UPDATE Product.sales_cnt = SUM of OrderItem.quantity per product_id
- Coupon.issued stays 0 (reserved for Phase 3-4)

## Conventions
- All entities: `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, `@Getter`
- No Setters. Use business methods instead.
- All entities: `@EntityListeners(AuditingEntityListener.class)`
- Application class: `@EnableJpaAuditing`
- After Phase 2 completion: change `ddl-auto` from `create` to `validate`

## Done Criteria
- [ ] `./gradlew bootRun` creates all tables
- [ ] Hibernate logs show CREATE TABLE queries
- [ ] init-data.sql seeds basic data
- [ ] generate-test-data.sh creates bulk data
- [ ] Verify: Product 100K, Order 50K row counts
- [ ] `ddl-auto: validate` boots without error
- [ ] OrderItem → Product LAZY relationship works
