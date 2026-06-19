# High Traffic Performance Tuning Roadmap

상품 100만 건·주문 대규모 데이터 트래픽 환경에서 성능 병목을 4개 시나리오(v1~v4 단계별)로 개선·측정하는 포트폴리오 프로젝트의 작업 계획. 세부 실행 지시는 `CLAUDE.md`·`specs/` 각 phase 스펙·`docs/PR_CONVENTION.md`를 참조.

레포: https://github.com/data-sy/high-traffic-performance-tuning

---

## Now — 진행 중

현재 활성 작업 없음 — **Phase 3-6 스케일업 완료**(아래 Done). 다음 후보는 **Phase 3-7 N+1**(Later) — 스케일업된 1M/1.5M 토대 위에서 최종 규모로 측정.

---

## Next — 다음 (착수 예정)

- Phase 3-6(스케일업) 완료(아래 Done) → 그 다음은 **Phase 3-7 N+1**(아래 Later, 스케일업된 규모에서 측정)·**4개 시나리오 종합 리포트**(아래 Later). 우선순위가 오르면 여기로 승격.

---

## Later — 백로그 (아직 미착수, 검토 단계)

- **[Phase 3-7 / 시나리오 ②] N+1 해결 — 주문 상세 Fetch Join** — 새 브랜치 (예: `phase/3-7-n-plus-one`) · **스케일업(②) 뒤로 연기**
  - 단계: **v1** LAZY default → **v2** EAGER → **v3** `@BatchSize` → **v4** Fetch Join
  - 엔티티 준비 완료: `OrderItem → Product @ManyToOne(LAZY)` (N+1 재현용, phase2 정의)
  - 스펙 미작성 — 착수 전 `specs/phase3/phase3-7-n-plus-one.md` 작성 필요
  - 권장: 위 스케일업(②)과 묶어 최종 규모에서 한 번에 측정 (재측정 중복 회피)
- **[문서] 4개 시나리오 측정 결과 종합 리포트** — 포트폴리오용으로 인덱스·N+1·랭킹·동시성 결과를 한 문서로 종합 (각 `results/phaseN-*/` 기반, ①의 Grafana 대시보드 캡처 포함)
- **[정리] 로컬 stale/거버넌스 브랜치 일괄 정리** — 지금은 보류, 나중 일괄. 머지 완료 브랜치(`fix/redis-config` #11, `phase/3-3-multi-instance` #10, `phase/3-4-async-issuance` #13)는 즉시 삭제 가능. `*-mainrun`·`sandbox/*`는 로컬 고유 내용이 있어 **필요한 정보만 추출 후 삭제**
- **[Phase 3-5 후속] concurrency 503 분해 패널 실데이터 캡처** — `concurrency.json`의 ⓐ/ⓑ/ⓒ(503 원인) 패널은 정상이나, 503을 내려면 풀 포화/락대기 유발 부하(쿠폰 재고·Hikari 풀 크기 등 파라미터 조정)가 필요. 향후 동시성 재측정 맥락에서 실데이터로 캡처
- (아이디어 추가 시 여기로)

---

## Done — 완료

날짜·PR 번호는 git history 기준. 상세 변경 내역은 각 PR 또는 `specs/` 해당 phase 스펙 참조.

- **[Phase 3-6 / 측정] 데이터 스케일업 — 100만/150만 대용량 재측정** — 2026-06-19 (PR [#15](https://github.com/data-sy/high-traffic-performance-tuning/pull/15), `phase/3-6-scale-up`)
  - `product` 1,000,000 / `order_item` 1,500,000 적재 후 시나리오① 인덱스·③ 랭킹을 동일 런 내부에서 재측정(전 step/ver 비포화 게이트 PASS — `http_req_failed≈0`·Hikari `pending==0`·statement-timeout 0)
  - **① 인덱스**: 무인덱스 `type=ALL`+filesort 319.6ms → 복합 인덱스 `type=ref`(no filesort) 12.7ms = **25.2×**. 잘못된 단일 인덱스(step3/5)는 `type=index`라도 무인덱스보다 느림(7~8s)도 재현
  - **③ 랭킹**: DB 1.5M `GROUP BY` 풀스캔(`type=ALL`) 39.3s → Redis Sorted Set 6.9ms = **약 5,700×**
  - cross-scale 정량은 인프라-불변 `EXPLAIN rows`로: 무인덱스 스캔량 100K→1M 약 **10배**(99,742→993,313)
  - 결과: `results/phase3-6-scale-up/`(EXPLAIN + k6 + 비포화 게이트), 회고 [`docs/reports/phase3-6-scale-up.md`](docs/reports/phase3-6-scale-up.md). 랭킹 대시보드 before/after 캡처는 후속 전시 보강
- **[Phase 3-5 / 인프라·관측성] Grafana 관측 스택 코드화 (provisioning-as-code)** — 2026-06-16 (PR [#14](https://github.com/data-sy/high-traffic-performance-tuning/pull/14), `phase/3-5-monitoring`)
  - datasource(고정 `uid=prometheus`) + 시나리오 4종 대시보드(index/ranking/concurrency/async) + provider를 repo에 정의하고 compose에 bind-mount → `docker compose up`만으로 자동 로드(수동 클릭 0, `grafana-storage` 볼륨 불변)
  - 두 메트릭 소스 대시보드화: (a) actuator 스크레이프(HikariCP·HTTP·executor 큐) (b) k6 remote-write. PoC에서 k6 실명(`k6_`+`_total`/`_p95`·`_p99`) 확정 후 PromQL에 반영
  - 결과: `docker/grafana/`, runbook `docker/grafana/README.md`, PoC `specs/phase3/poc-phase3-5-verification.md`, Step D 스크린샷 `results/phase3-5-monitoring/screenshots/`(랭킹 v1 DB 107ms vs v4 Redis 0.8ms before/after)
- **[시나리오 / 비동기 발급] 쿠폰 비동기 발급 — 큐 구조 사다리** — 2026-06 (PR [#13](https://github.com/data-sy/high-traffic-performance-tuning/pull/13), `phase/3-4-async-issuance`)
  - **v6a** @Async 인메모리 스레드풀 → **v6b** Redis List(BRPOP·ack 없음, 유실 재현) → **v6c** Redis Stream(컨슈머 그룹·PEL·XACK, 재전달 흡수)
  - 동기 발급(v3)의 "줄을 큐로 옮긴" 비용 프로파일 측정: 202 응답 ↔ E2E 발급 지연·큐 깊이·수렴 시간. 동시성 게이트(초과발급 0)는 유지
  - 결과: `results/phase3-4-async/`, 회고 `docs/reports/phase3-4-async-issuance.md`, 스펙(역방향) `specs/phase3/phase3-4-async-issuance.md`
- **[시나리오 ④ / 검증] 다중 인스턴스 정합 실증 (보강#1)** — 2026-06 (PR [#10](https://github.com/data-sy/high-traffic-performance-tuning/pull/10), `phase/3-3-multi-instance`)
  - 동일 v1~v5 락 코드 + 다중 인스턴스 토폴로지(앱 N replica + Nginx LB + Prometheus 타깃 N개), 하네스 무수정 재사용(`-e BASE=<LB>`·`noConnectionReuse` 설정만)
  - 결과: **v2(synchronized) 붕괴 재현** + **v3~v5 경계초월 정합 유지**(Σ 인스턴스 == DB) → "다중서버 = 공유 좌표 필요, v3가 이미 제공"을 데이터로 확정. "단일 JVM 미실증" 정직성 꼬리표 제거
- **[시나리오 ④] 동시성 제어 — 쿠폰 발급 락 사다리** — 2026-06-08 (PR [#8](https://github.com/data-sy/high-traffic-performance-tuning/pull/8), [#9](https://github.com/data-sy/high-traffic-performance-tuning/pull/9), `phase/3-3-concurrency-lock`)
  - **v1** no lock(초과 999 재현) → **v2** synchronized → **v3** SELECT FOR UPDATE(★ 프로덕션 채택) → **v4** Redisson RLock → **v5** 커스텀 SET NX+Lua, + fencing 스톨 데모
  - 워크로드 적합성으로 v3 채택(락↔데이터 동치 → fencing 무공백). throughput 버전 간 직접 비교 금지 규율, 통제변수 핀, 503 ⓐ/ⓑ/ⓒ 분류
  - 결과: `results/phase3-3-concurrency/`(analysis.md 동결), 회고 `docs/reports/phase3-3-concurrency-lock.md`, 스펙 `specs/phase3/phase3-3-concurrency.md`
  - 다중 인스턴스 정합: 보강#1로 분리 실증 완료(아래 항목)
- **[인프라] 모니터링 인프라 구축** — 2026-02-24 (PR [#6](https://github.com/data-sy/high-traffic-performance-tuning/pull/6))
  - Spring Boot Actuator + Prometheus 메트릭 엔드포인트, Docker Compose에 Prometheus·Grafana 추가
  - k6 Prometheus remote write 출력 + step 태깅·trend 통계
- **[시나리오 ③] 실시간 랭킹 — Redis Sorted Set** — 2026-02-09 (PR [#4](https://github.com/data-sy/high-traffic-performance-tuning/pull/4), [#5](https://github.com/data-sy/high-traffic-performance-tuning/pull/5), phase3-2)
  - **v1** DB ORDER BY → **v2** DB index → **v3** Redis String → **v4** Redis Sorted Set(실시간 갱신)
  - Redis 인프라·DTO·리포지토리, PoC 검증, race condition 테스트, 성능 비교 결과 저장
  - 스펙: `specs/phase3/phase3-2-ranking_en.md`, `specs/phase3/poc-phase3-2-verification_en.md`
- **[시나리오 ①] 인덱스 최적화 — 복합 인덱스** — 2026-02-05 (PR [#3](https://github.com/data-sy/high-traffic-performance-tuning/pull/3), phase3-1)
  - **v1** no index → **v2** category → **v3** created_at → **v4** composite(+ v5 reverse composite 비교)
  - 상품 목록 API(카테고리 필터·페이징), EXPLAIN 측정 자동화, k6 부하 테스트, PoC 검증
  - 스펙: `specs/phase3/phase3-1-index-optimization.md`, `specs/phase3/poc-phase3-1-verification.md`
- **[Phase 2] 도메인 엔티티** — 2026-02-03 (PR [#2](https://github.com/data-sy/high-traffic-performance-tuning/pull/2))
  - 4개 시나리오용 엔티티·리포지토리, 시드 데이터 + 대량 테스트 데이터 생성 스크립트, `ddl-auto` create→validate 전환
  - 스펙: `specs/phase2/phase2-domain-entity.md`
- **[Phase 1] 프로젝트 셋업** — 2026-02-03 (PR [#1](https://github.com/data-sy/high-traffic-performance-tuning/pull/1))
  - Spring Boot 설정·디렉터리 구조, Docker Compose(MySQL 8·Redis 7), PR 컨벤션, Claude 프로젝트 가이드라인
  - 스펙: `specs/phase1/phase1-project-setup.md`

---

## 작업 단위 분할 원칙

이 프로젝트는 4개 시나리오를 phase 단위로 진행하는 단일 스키마 모놀리식이라, 무거운 Epic/Milestone 구조 대신 phase-spec 중심의 가벼운 체계를 사용한다.

표준 PM 용어와의 대응(리네이밍이 아니라 매핑 — repo의 작업 단위 명칭은 "Phase"로 통일한다):

| 표준 PM | 이 프로젝트 | 예 |
|---|---|---|
| Epic | 프로젝트 전체 (한 덩어리) | high-traffic-performance-tuning |
| **Milestone** | **Phase** | Phase 1·2(셋업·도메인), Phase 3(시나리오 최적화·측정), Phase 4(대용량 분산처리 — 예정) |
| Task/Story | 시나리오·Spec | 3-1 인덱스, 3-2 랭킹, 3-3 동시성, 3-4 비동기 발급, 3-5 관측성, 3-6 스케일업 |

- Phase가 Milestone 역할을 한다(별도 Milestone 계층 없음). 이미 시작된 넘버링(~3-4)은 유지하고 소급 리네이밍하지 않는다.
- 칸반 상태(Now/Next/Later/Done)는 위 단위들과 직교한다 — 단위는 "무엇"을, 상태는 "어디까지"를 가리킨다.

- **Roadmap** — 모든 작업의 단일 인덱스 (이 문서)
- **Spec** — `specs/phaseN/` 디렉토리별 단계 구현 명세(phase1·phase2·phase3). 각 스펙을 그대로 따라 구현 (`CLAUDE.md` 규칙)
- **PoC** — 도구·접근 사전 검증은 해당 phase 디렉토리의 `poc-*.md`
- **Templates** — 신규 phase 작성 시 `specs/templates/`(phase-spec-template·phase-human-checklist-template) 참조
- **CLAUDE.md** — 프로젝트 규칙·엔티티·관계·API 구조·컨벤션 (불변 규칙)
- **PR_CONVENTION.md** — PR 작성 규칙 (`docs/`)

## 갱신 규칙

- 새 작업 착수 시 Now로 이동, 관련 브랜치명 함께 기록
- 완료 시 Done으로 이동, 완료일·PR 번호 기록
- 새 아이디어는 Later에 먼저 추가하고, 우선순위가 올라가면 Next로 승격
- 보류된 작업은 Next에 두고 "보류 사유" 명시
- 각 시나리오는 v1~v4 모든 버전이 코드에 공존 (독립 비교·재측정용, `CLAUDE.md` API 구조 규칙)
