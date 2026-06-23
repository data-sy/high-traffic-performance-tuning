# Phase 3-7 — R3 부하 모델 전제 검증 (버퍼 워밍 함정)

> design-review R3 / 메모리 `dryrun-pin-runtime-assert`. 측정 **전** 1회 확인. 측정일에 재확인.

## 전제: `innodb_buffer_pool_size` < 워킹셋

InnoDB 버퍼풀이 데이터셋 전체를 담으면, userId를 분산해도 워밍 후 전량 캐시라 p95가 N+1을 못 가른다(무효).
풀이 워킹셋보다 작아야 분산된 요청이 실제 디스크 I/O를 일으켜 구조적 차이가 p95로 드러난다.

## 실측 (측정일 재확인 대상)

| 항목 | 값 |
|---|---|
| `innodb_buffer_pool_size` | 134,217,728 B = **128 MiB** |
| `orders` (data+index) | 25.6 MiB |
| `order_item` (data+index) | 124.2 MiB |
| `product` (data+index) | 152.3 MiB |
| **워킹셋 합** | **~302 MiB** |

**판정: PASS** — 버퍼풀(128 MiB) ≪ 워킹셋(~302 MiB). `product` 단독(152 MiB)도 풀을 초과.
→ userId를 ~1,000명에 랜덤 분산하면 풀에 다 안 담겨 디스크 I/O 발생 → p95 분리 여지 확보.
→ 그래도 목록의 1차 근거는 쿼리 수(격리 런), p95는 보조(R3).

## 측정 규칙 재확인
- 쿼리 수: 격리 런(VU=1, 단발 요청), SQL 로그 라인 카운트.
- p95: 부하 런, k6 userId/orderId 랜덤 분산(워킹셋 > 풀).
- 상세(M≈3)는 워밍 시 노이즈 → **쿼리 수로만** 가른다(R3 #7).
- 비포화 게이트: `http_req_failed≈0`, Hikari `pending==0`.
