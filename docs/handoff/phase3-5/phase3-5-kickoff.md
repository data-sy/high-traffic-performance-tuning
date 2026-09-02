# Phase 3-5 킥오프 — Grafana 프로비저닝-as-code

> **상태: ✅ 완료 (2026-06-16 · PR [#14](https://github.com/data-sy/high-traffic-performance-tuning/pull/14)).** 이 문서는 착수 시점의 킥오프 기록이다 — **지금 이 지시를 따라 새로 진행하지 말 것.** 결과는 `docker/grafana/`·`results/phase3-5-monitoring/`, 현재 진행 중인 작업은 [`ROADMAP.md`](../../../ROADMAP.md) `## Now`가 정본.

> <sub>(원문 보존) 다음 세션에서 이 파일을 읽고 진행하세요. ("이 md 읽고 진행해줘")</sub>

## 작업
Phase 3-5 — Grafana 관측 스택을 **프로비저닝-as-code**로 구현.

- 저장소: `/Users/owner/high-traffic-performance-tuning`
- 브랜치: **`phase/3-5-monitoring`** (이미 생성됨 — 여기서 작업·커밋). **main push 금지, PR 필수.**

## 먼저 읽을 계약 문서
- @specs/phase3/phase3-5-monitoring.md — 본 구현 스펙 (Step A~D)
- @specs/phase3/poc-phase3-5-verification.md — 착수 전 PoC 3종
- @CLAUDE.md — 프로젝트 규칙 (엔티티·관계·Docker·로깅 등)
- @ROADMAP.md — "Now" 항목(① Phase 3-5)에 맥락

## 목표
현재 Grafana는 **컨테이너만** 있고 datasource·대시보드가 손으로 설정돼 `grafana-storage` 볼륨에만 존재(재현 불가). 이를 `docker/grafana/provisioning/` + `docker/grafana/dashboards/`로 **코드화**해서 `docker compose up`만으로 자동 로드되게 하고, 시나리오 v1~v4 before/after 대시보드를 구축한다.

## 진행 순서
1. **PoC 먼저** (@specs/phase3/poc-phase3-5-verification.md 의 PoC 1~3)
   - PoC 1: Prometheus에 actuator 시계열 + k6 remote-write 시계열이 **실제 쿼리되는지** (대시보드에 박을 메트릭 이름을 여기서 확정).
   - PoC 2: datasource 프로비저닝(`provisioning/datasources/*.yml`)이 자동 연결되는지.
   - PoC 3: dashboard 프로비저닝(provider + JSON) 자동 로드 + `grafana-storage` 볼륨과 경로 충돌 없는지.
   - ※ `docker compose up`·브라우저(`localhost:3000`) 확인·k6 remote-write 실행은 **사람이 하는 단계** — 사용자에게 `! <명령>`으로 실행을 요청할 것.
2. **PoC 통과 후 본 구현**: Step A(datasource) → B(dashboard provider + JSON) → C(compose 마운트) → D(검증).

## 규칙
- 기존 compose 서비스 깨지 않기. **`grafana-storage` 볼륨 삭제 금지** (`down -v` 금지).
- PoC 1에서 **확정한 실제 메트릭 이름**을 대시보드 쿼리에 사용 — 지어내지 말 것.
- 커밋은 깨끗한 경계마다 **먼저 물어보고**. 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` 푸터.

## 참고 (이전 세션 결과)
- `phase/3-5-monitoring` 브랜치엔 ROADMAP 재정렬·specs 재구성(phase1/2/3 디렉토리)·phase3-4 역방향 spec·phase3-5 spec·PoC가 이미 커밋됨(미push).
- 넘버링: 3-5 모니터링 / 3-6 스케일업 / 3-7 N+1.
