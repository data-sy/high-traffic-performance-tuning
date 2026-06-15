# HANDOFF — Phase 3-3 동시성 락 (샌드박스 리허설 → 본 실행)

> 이 묶음은 리허설 워크트리(`sandbox/phase3-3-dryrun`)에서 본 실행(`phase/3-3-concurrency-lock`)으로
> 넘기는 핸드오프다. **개정 스펙 + 검증·일반화 끝난 하네스 + 전이 트랩 + 통제 핀 + 빈 분석 템플릿.**
> 본 실행은 이걸 정규 위치로 복사하고, **락 구현·런·분석만 새로 생성**한다(스펙 §0).

## 1. 번들 파일 목록 + 본 레포 배치 위치

| 번들 파일 | 본 레포 배치 위치 | 처치 |
|-----------|-------------------|------|
| `phase3-3-concurrency-mainrun.md` | `specs/phase3-3-concurrency.md` (또는 참조 보관) | 개정 스펙(audit Healthy). 본 실행의 단일 진실 |
| `coupon-test.js` | `test/load/coupon-test.js` | **무수정 복사** |
| `run-coupon-concurrency.sh` | `scripts/measure/run-coupon-concurrency.sh` | **무수정 복사** + `chmod +x` |
| `reset-coupon.sql` | `scripts/coupon/reset-coupon.sql` | **무수정 복사** |
| `analysis.md` | `results/phase3-3-concurrency/analysis.md` | 빈 템플릿 — 본 실행이 채움 |
| `PINS.md` | (참조 — src 복사 아님) | 통제 프로필·성공 시그니처·reset 게이트. 런 세션이 읽음 |
| `TRANSFERABLE-TRAPS.md` | (참조) | 전이 트랩 + 샌드박스 전용 N/A. 런 세션이 읽음 |
| `HANDOFF.md` | (참조) | 이 문서 |

- `results/phase3-3-concurrency/{k6/, integrity/}`는 본 실행 런 세션이 생성(산출물). 구체 파일 명명은 phase3-1·3-2 관례에 맞춤(스펙 §10).

## 2. 필요한 조정 — 단 하나: 컨테이너 타겟 (하네스 코드는 무수정)

> 하네스는 이미 `${COMPOSE_PROJECT_NAME:-docker_sandbox}-{mysql,redis}-1`로 파라미터화돼 있다.
> **코드를 고치지 말고 환경값으로 타겟을 맞춘다.**

- 본 실행 셋업에서 **`export COMPOSE_PROJECT_NAME=docker`** (본 compose 프로젝트명) → 타겟이 `docker-mysql-1`/`docker-redis-1`.
- ⚠️ 미export 시 default `docker_sandbox-mysql-1`을 직격 → 본 레포 컨테이너 미접촉 → 첫 `run_mysql`에서 abort. (default를 `docker`로 *바꾸지 않는* 이유: 그건 하네스 수정이라 무수정 원칙에 금이 가고, 샌드박스 재사용 시 거꾸로 샌드박스를 직격. 환경 export가 양쪽에 안전 — 스펙 §7.)
- 앱은 로컬 `bootRun`(localhost:8080), DB/Redis는 localhost:3306/6379(컨테이너가 소유). `BASE`는 `coupon-test.js` 기본 `http://localhost:8080` 그대로.

## 3. 본 실행이 통과할 검증 게이트 (스펙 §2 — v1 측정 진입 전 1회)

1. **포트 free + 타겟 컨테이너 실존(한 쌍)**: 3306/6379 바인딩 + `COMPOSE_PROJECT_NAME=docker` 상태에서 `docker ps`에 `docker-mysql-1`/`docker-redis-1` 실존(리허설 `docker_sandbox-*` 직격 안 함).
2. **스키마 스모크**: `SHOW TABLES`에 `coupon`/`coupon_issue` 포함 6테이블 + `validate` 부팅. (빈 볼륨이면 §2.2 부트스트랩 — TRANSFERABLE-TRAPS 발견1.)
3. **하네스 스모크**: 각 버전 엔드포인트 단건 POST → 기대 status.
4. **통제변수 핀 대조**: 런타임 assert가 전역 `innodb_lock_wait_timeout=50`/`REPEATABLE-READ` 일치(불일치 abort).
5. **★ reset 게이트 (PINS.md)**: 각 런(버전·축) 시작 전 `reset-coupon.sql` 필수 — 성능축이 `total_qty`를 1억으로 올린 뒤 미복원하므로, reset 누락 시 후속 정합성 측정 오염. (하네스가 각 축 시작에 reset 내장; 수동/축외 실행 시 직접 보장.)

## 4. 알려진 한계 — 리포트 축 라벨 하드코딩 (무해, 코드 무수정 유지)

- `run-coupon-concurrency.sh`의 python 리포트 블록(line 127/143)이 축 라벨을 `"constant-vus 500 / 10s"`·`"warmup 5s + steady 30s"`로 **고정 문자열** 출력. **데이터값은 실제 env 반영(정상), 설명 라벨만 고정.**
- **본 실행은 기본 부하(500/10s·5s/30s)라 라벨 = 실값 일치 → 무해.** 비기본 env로 측정할 때만 라벨이 오해를 줌(데이터값만 신뢰).
- **하네스 코드 무수정 원칙 유지** — 라벨을 env 변수로 바꾸는 정리는 본 실행/별도 작업으로(번들·복사 단계에서 하네스 수정 금지). 상세·분류: `TRANSFERABLE-TRAPS.md` 클래스 B ★NEW.

## 5. 본 실행 작업 순서 (스펙 §12)

게이트(§2) → Step A(도메인+DTO+시드) → Step B(v1+하네스 도입+부하 확정) → C(v2) → D(v3) → E(v4 워치독) → F(v5 고정TTL + 선택 스톨데모) → G(종합 비교·3축 서사·throughput 비교 금지·프로덕션 v3 결정) → `/pr`. 각 Step: 구현→측정→`analysis.md` 기록→커밋.
