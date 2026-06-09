# High Traffic Performance Tuning Roadmap

100만 회원 규모 트래픽 환경에서 성능 병목을 4개 시나리오(v1~v4 단계별)로 개선·측정하는 포트폴리오 프로젝트의 작업 계획. 세부 실행 지시는 `CLAUDE.md`·`specs/` 각 phase 스펙·`docs/PR_CONVENTION.md`를 참조.

레포: https://github.com/data-sy/high-traffic-performance-tuning

---

## Now — 진행 중

- **[시나리오 ④ / 검증] 다중 인스턴스 정합 실증 (보강#1)** — `phase/3-3-multi-instance` (브랜치 생성 완료)
  - 목적: 현재 단일 JVM 측정으론 **미실증(개념적 보장)**인 다중 인스턴스 정합을 **실측으로 승격**. phase3-3 결론(v3 채택)을 *반증이 아니라 강화*하는 실험
  - 구성: 동일 phase3-3 동시성 락 코드(v1~v5) 그대로, 토폴로지만 다중 인스턴스로. 기존 하네스(`coupon-test.js` 등) 무수정 재사용
  - 기대 결과(가설): **v2(synchronized) 붕괴**(JVM 로컬 모니터 → 초과발급 재발) + **v3~v5 경계초월 정합 유지**(공유 DB 행/공유 Redis). → "다중서버 = Redis 분산락 필요"가 아니라 "공유 좌표 필요, 그건 v3가 이미 줌"을 데이터로 확정
  - 효과: `analysis.md §한계`·회고 §8·이력서/포폴의 "단일 JVM 미실증" 정직성 꼬리표를 **떼고** "다중 인스턴스 정합 실증"으로 승격
  - **선행 작업**(실제 비용은 모니터링이 아니라 토폴로지): 앱 Docker 이미지화 + compose에 N replica + Nginx LB(현재 앱은 호스트에서 1개로 구동, compose에 app 서비스 없음) + Prometheus 타깃 N개로 확장

---

## Next — 다음 (착수 예정)

- **[정리] k6 결과 phase 디렉터리 재배치 PR 머지** — PR [#7](https://github.com/data-sy/high-traffic-performance-tuning/pull/7) (`chore/reorg-k6-results`)
  - 재배치 커밋(`d3fd12b`)이 별도 브랜치에 파킹됨. main 보호 규칙(PR 필수)으로 직접 push 불가 → PR로만 머지
  - 머지 후 작업 브랜치들이 새 결과 레이아웃(`results/phaseN-*/k6/`)을 따라가도록 동기화
- **[문서] 동시성 스펙 확정** — `specs/phase3-3-concurrency-draft.md` → refine 후 `-draft` 제거
  - 초안 작성 완료. 다른 세션에서 정돈 + adapted `/audit-doc`로 검증 후 확정
  - 미해결 결정: v4 Redis 락 방식(SET NX+Lua vs Lua 카운터), 분산환경(Docker 3인스턴스+Nginx) 포함 여부, Fallback/서킷브레이커 포함 여부 (초안 "보강 후보" 섹션 참조)
---

## Later — 백로그 (아직 미착수, 검토 단계)

- **[인프라/측정] 데이터 스케일업 + 인덱스·랭킹 재측정** — 새 브랜치 (예: `phase/3-5-scale-up`)
  - 목적: 포폴 헤드라인("100만 규모")과 실데이터 정합. **성능 병목 테이블은 users(현재 1K)가 아니라 product(100K)·orders(50K)** — 회원만 늘려선 인덱스·Redis 수치가 안 바뀌므로 product·orders를 키워야 함
  - 범위: `generate-test-data.sh` 수정(product 100K→100만, orders 확장) → 시나리오① 인덱스(EXPLAIN+k6 5스텝) 재측정 → 시나리오③ 랭킹(DB ORDER BY vs Redis) 재측정 → 각 `results/`·문서 수치 갱신
  - 권장 묶음: 재측정 중복을 피하기 위해 **이 스케일업 → 인덱스·랭킹 재측정 → N+1**을 한 흐름으로 처리. 즉 N+1 승격 시 함께 진행
  - 주의: 동시성(④)은 스케일 무관(동시 요청 수에 의존)이라 이 작업의 선행 대상 아님. 출시/제출 전엔 신뢰도 위해 1회 필수
- **[시나리오 ②] N+1 해결 — 주문 상세 Fetch Join** — 새 브랜치 (예: `phase/3-4-n-plus-one`) · **동시성 뒤로 연기**
  - 단계: **v1** LAZY default → **v2** EAGER → **v3** `@BatchSize` → **v4** Fetch Join
  - 엔티티 준비 완료: `OrderItem → Product @ManyToOne(LAZY)` (N+1 재현용, phase2 정의)
  - 스펙 미작성 — 착수 전 `specs/phase3-4-n-plus-one.md` 작성 필요
  - 권장: 위 스케일업과 묶어 최종 규모에서 한 번에 측정 (재측정 중복 회피)
- **[인프라] Grafana 시나리오별 before/after 대시보드** — 모니터링 인프라(PR [#6](https://github.com/data-sy/high-traffic-performance-tuning/pull/6)) 위에 각 시나리오 v1~v4 비교를 시각화하는 대시보드 정의
- **[문서] 4개 시나리오 측정 결과 종합 리포트** — 포트폴리오용으로 인덱스·N+1·랭킹·동시성 결과를 한 문서로 종합 (각 `results/phaseN-*/` 기반)
- (아이디어 추가 시 여기로)

---

## Done — 완료

날짜·PR 번호는 git history 기준. 상세 변경 내역은 각 PR 또는 `specs/` 해당 phase 스펙 참조.

- **[시나리오 ④] 동시성 제어 — 쿠폰 발급 락 사다리** — 2026-06-08 (PR [#8](https://github.com/data-sy/high-traffic-performance-tuning/pull/8), [#9](https://github.com/data-sy/high-traffic-performance-tuning/pull/9), `phase/3-3-concurrency-lock`)
  - **v1** no lock(초과 999 재현) → **v2** synchronized → **v3** SELECT FOR UPDATE(★ 프로덕션 채택) → **v4** Redisson RLock → **v5** 커스텀 SET NX+Lua, + fencing 스톨 데모
  - 워크로드 적합성으로 v3 채택(락↔데이터 동치 → fencing 무공백). throughput 버전 간 직접 비교 금지 규율, 통제변수 핀, 503 ⓐ/ⓑ/ⓒ 분류
  - 결과: `results/phase3-3-concurrency/`(analysis.md 동결), 회고 `docs/reports/phase3-3-concurrency-lock.md`, 스펙 `specs/phase3-3-concurrency.md`
  - 남은 한계: 다중 인스턴스 정합 미실증 → 보강#1로 분리(현재 Now)
- **[인프라] 모니터링 인프라 구축** — 2026-02-24 (PR [#6](https://github.com/data-sy/high-traffic-performance-tuning/pull/6))
  - Spring Boot Actuator + Prometheus 메트릭 엔드포인트, Docker Compose에 Prometheus·Grafana 추가
  - k6 Prometheus remote write 출력 + step 태깅·trend 통계
- **[시나리오 ③] 실시간 랭킹 — Redis Sorted Set** — 2026-02-09 (PR [#4](https://github.com/data-sy/high-traffic-performance-tuning/pull/4), [#5](https://github.com/data-sy/high-traffic-performance-tuning/pull/5), phase3-2)
  - **v1** DB ORDER BY → **v2** DB index → **v3** Redis String → **v4** Redis Sorted Set(실시간 갱신)
  - Redis 인프라·DTO·리포지토리, PoC 검증, race condition 테스트, 성능 비교 결과 저장
  - 스펙: `specs/phase3-2-ranking_en.md`, `specs/poc-phase3-2-verification_en.md`
- **[시나리오 ①] 인덱스 최적화 — 복합 인덱스** — 2026-02-05 (PR [#3](https://github.com/data-sy/high-traffic-performance-tuning/pull/3), phase3-1)
  - **v1** no index → **v2** category → **v3** created_at → **v4** composite(+ v5 reverse composite 비교)
  - 상품 목록 API(카테고리 필터·페이징), EXPLAIN 측정 자동화, k6 부하 테스트, PoC 검증
  - 스펙: `specs/phase3-1-index-optimization.md`, `specs/poc-phase3-1-verification.md`
- **[Phase 2] 도메인 엔티티** — 2026-02-03 (PR [#2](https://github.com/data-sy/high-traffic-performance-tuning/pull/2))
  - 4개 시나리오용 엔티티·리포지토리, 시드 데이터 + 대량 테스트 데이터 생성 스크립트, `ddl-auto` create→validate 전환
  - 스펙: `specs/phase2-domain-entity.md`
- **[Phase 1] 프로젝트 셋업** — 2026-02-03 (PR [#1](https://github.com/data-sy/high-traffic-performance-tuning/pull/1))
  - Spring Boot 설정·디렉터리 구조, Docker Compose(MySQL 8·Redis 7), PR 컨벤션, Claude 프로젝트 가이드라인
  - 스펙: `specs/phase1-project-setup.md`

---

## 작업 단위 분할 원칙

이 프로젝트는 4개 시나리오를 phase 단위로 진행하는 단일 스키마 모놀리식이라, 무거운 Epic/Milestone 구조 대신 phase-spec 중심의 가벼운 체계를 사용한다.

- **Roadmap** — 모든 작업의 단일 인덱스 (이 문서)
- **Spec** — `specs/phaseN-*.md` 단계별 구현 명세. 각 스펙을 그대로 따라 구현 (`CLAUDE.md` 규칙)
- **PoC** — 도구·접근 사전 검증은 `specs/poc-*.md`
- **Templates** — 신규 phase 작성 시 `specs/templates/`(phase-spec-template·phase-human-checklist-template) 참조
- **CLAUDE.md** — 프로젝트 규칙·엔티티·관계·API 구조·컨벤션 (불변 규칙)
- **PR_CONVENTION.md** — PR 작성 규칙 (`docs/`)

## 갱신 규칙

- 새 작업 착수 시 Now로 이동, 관련 브랜치명 함께 기록
- 완료 시 Done으로 이동, 완료일·PR 번호 기록
- 새 아이디어는 Later에 먼저 추가하고, 우선순위가 올라가면 Next로 승격
- 보류된 작업은 Next에 두고 "보류 사유" 명시
- 각 시나리오는 v1~v4 모든 버전이 코드에 공존 (독립 비교·재측정용, `CLAUDE.md` API 구조 규칙)
