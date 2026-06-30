# Phase 3-8 — 무효 런 보존

> 측정 유효성 게이트(M5)에 걸린 런을 *삭제하지 않고* 여기에 사유와 함께 보존한다(거짓 수렴 학습 자료).

## INVALID 사유 분류
- **핀 불일치:** `ops /pins` ≠ 기대(TTL/포트/prefix) → abort
- **인덱스 부재(AD-1):** EXPLAIN type≠ref / filesort 발생 → baseline 오염
- **격리 위반:** 본체 Redis(6379)에 `cache:product:list:*` 키 검출
- **락 리스 불변 위반(DR-4):** 관측 전 구간 p99 ≥ LOCK_TIMEOUT_MS → v3 이중 재계산
- **statement-timeout 실패:** 버스트축에서도 INVALID (단, Hikari `pending>0` 자체는 정상 — DR-GATE-1)
- **배리어 미충족(DR-TOMCAT-1):** C가 tomcat threads.max 근접 → latch n 미충족 → 무한 대기 타임아웃
- **워밍 측정(M3):** 만료 전환점 못 잡고 따뜻한 캐시로 측정 → herd 미검출(자가검증 b가 드러냄)
- **도착 산포(DR-A):** 배리어 없이 발사 → v2 herd≪C 과소관측(자가검증 c가 드러냄)

## 기록

### 2026-06-30 축A 스탬피드 — latent charset 버그(빈 결과 → 거짓 herd) — `stampede/stampede-20260630-050607.tsv`
- **증상:** v1=50 v2=50 **v3=50**(blocked=49) v4=1 v5=50. v3 가 single-flight 인데 재계산 50.
- **사유:** 1M `product.category` 가 이중 인코딩(cp1252) 저장인데 측정 스택 JDBC 세션이 utf8mb4 라
  `category='전자기기'` 가 **0행** 매칭 → 빈 결과 → `EMPTY_AS_MISS` 로 미적재 → v3 홀더도 매번 빈 결과를
  재로드(캐시 영영 미스) → 직렬 50회 재계산. v4 만 1(논리 적재가 빈 payload 라도 물리 존재 → 스테일 반환).
- **교정:** 측정 스택 `SET NAMES latin1`(+characterEncoding=UTF-8)로 저장 인코딩 매칭 → 200000행 확정.
  재측정 `stampede-20260630-051853.tsv`(v3=1·blocked=49)이 유효 런. 상세 §메타회고(2) · docs/reports.
