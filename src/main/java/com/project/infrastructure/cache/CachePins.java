package com.project.infrastructure.cache;

import java.util.List;

/**
 * Phase 3-8 캐시 사다리 통제변수의 단일 소스 (핀 표와 1:1, M2·A-2).
 *
 * <p>사다리 내 <b>단일 변수 = 방어 기법만 차이</b>. TTL·지터·락·핫키·동시성·풀·격리는 전 버전 일치하며
 * {@link CachePinsBootAssert} 가 기동 시 실제 빈/설정과 대조(불일치 시 기동 실패)하고, 측정 하네스가
 * 런타임에 {@code GET /api/cache/ops/pins} 로 재대조(이중 게이트, phase4-1 EventPins 패턴 계승).
 *
 * <p><b>초안값 — 비준·실측 시 확정(Q3).</b> 만료 전환이 부하 런 안에 관측 가능한 TTL 인지, 락 리스/대기
 * 불변(전 구간 실측 p99 기준)이 성립하는지는 측정으로 핀한다. 여기 값은 부팅 가드용 보수값이다.
 */
public final class CachePins {

    // ── TTL (M2·A-2) ────────────────────────────────────────────────────────────
    /** 고정 TTL 기준값(ms). 만료 전환을 부하 런 안에서 관측 가능하게 — 초안값(Q3). */
    public static final long TTL_BASE_MS = 10_000;
    /** 널 캐싱 TTL(ms). 미존재 키 흡수 — 짧게(v5 페네트레이션). */
    public static final long NULL_TTL_MS = 2_000;
    /** v5 TTL 지터 비율(±). 동일 시각 일괄 만료 분산(어밸런치 방어). */
    public static final double TTL_JITTER_RATIO = 0.2;

    // ── 락 (v3 single-flight) ────────────────────────────────────────────────────
    /**
     * 락 리스/TTL(ms) — 홀더 크래시 시 인계 한도.
     * <b>불변(AD-5·DR-1): LOCK_TIMEOUT_MS &gt; 전 구간 실측 p99 재계산시간.</b>
     * 리스가 전 구간보다 짧으면 홀더 생존 중 리스 만료 → 이중 재계산(v3 재계산&gt;1). 부팅 가드는 보수값,
     * 수렴 게이트가 실측 p99 로 재대조.
     */
    public static final long LOCK_TIMEOUT_MS = 200;
    /**
     * 비홀더 단일 비행 대기 한도(ms). 초과 시 폴백.
     * <b>불변(DR-4): LOCK_WAIT_MS &gt; 홀더 완료 전 구간 실측 p99.</b> 대기 예산이 홀더 재계산 완료보다
     * 짧으면 비홀더가 완료 전 폴백 발화 → herd 회귀. 두 불변은 한 쌍: LOCK_WAIT_MS ≥ LOCK_TIMEOUT_MS ≥ 전구간 p99.
     */
    public static final long LOCK_WAIT_MS = 500;

    // ── v4 (논리 만료 + 비동기 갱신, CAS 단일 refresh) ─────────────────────────────
    /**
     * 논리 만료 리드타임 / 스테일 허용창(ms). 물리 TTL 이 끝나기 이 시간 전부터 "논리 만료(스테일)" 로 보고
     * 백그라운드 단일 refresh 를 트리거한다. 과하면 만료 전 잦은 재계산(낭비), 약하면 만료창 herd 잔존 — 측정 핀(Q7).
     * ({@code PER_BETA} 는 제외된 락 없는 PER 변종 전용이라 본 phase 미사용, DR-B.)
     */
    public static final long STALE_WINDOW_MS = 2_000;
    /** v4 논리 수명(ms) — 적재 후 이 시간 동안 논리적으로 신선, 이후 스테일(스테일 반환 + 단일 refresh 트리거). */
    public static final long V4_LOGICAL_LIFE_MS = TTL_BASE_MS;
    /**
     * v4 물리 TTL(ms) = 논리 수명 + 스테일 허용창. 논리 만료 후에도 STALE_WINDOW 동안 물리 값이 살아 있어야
     * 스테일 반환 + 백그라운드 refresh 가 물리 축출 전에 완료된다(물리 콜드 herd 회귀 회피·DR-V4COLD-1).
     */
    public static final long V4_PHYSICAL_TTL_MS = V4_LOGICAL_LIFE_MS + STALE_WINDOW_MS;

    // ── 핫 키 카디널리티 (고정 5종 카테고리 × page1) ──────────────────────────────
    /** 핫 카테고리 5종(CLAUDE.md). page1 기준 핫 키 카디널리티 = 5. */
    public static final List<String> HOT_CATEGORIES = List.of("전자기기", "의류", "식품", "도서", "스포츠");
    public static final int PAGE = 0;
    public static final int SIZE = 20;
    /** 페네트레이션 입력 후보 — 존재하지 않는 카테고리. */
    public static final String NONEXISTENT_CATEGORY = "없는카테고리";

    // ── 빈/널 적재 정책 (페네트레이션 단일 변수, 축B) ──────────────────────────────
    // 빈 Page 는 null 이 아닌 적재 가능 합법 값이라 적재 여부가 페네트레이션을 가른다.
    /** v2: 빈/널 = 미스 취급(적재 안 함) → 매 요청 직격 노출(결함 baseline). */
    public static final boolean V2_EMPTY_AS_MISS = true;
    /** v5: 빈/널 = 짧은 NULL_TTL 로 적재 → 흡수. */
    public static final boolean V5_EMPTY_AS_MISS = false;

    // ── 동시성 (버스트축 발사 규모 — 초안, Q3) ─────────────────────────────────────
    /** 스탬피드 동시성 C. 배리어(A-6)로 동기 release. C &gt; pool(=10) 이라 버스트 중 pending&gt;0 정상(M5). */
    public static final int STAMPEDE_CONCURRENCY = 50;
    /** 페네트레이션 동시성. v5 널 적재 전 창의 herd 크기 결정(축B). */
    public static final int PENETRATION_CONCURRENCY = 50;

    // ── 커넥션 풀 (DR-POOL-1) ─────────────────────────────────────────────────────
    /**
     * Hikari 최대 풀 크기(over-provision 금지). base application.yml(=본체)·전용 스택 동일.
     * 전 구간 실측 p99(= 체크아웃 대기 + SELECT + COUNT + 직렬화)와 '버스트 pending&gt;0=정상' 기준이
     * 풀 크기에 종속하므로 핀 단일 소스에 포함. 부팅 assert 가 실제 maximumPoolSize 와 대조.
     */
    public static final int HIKARI_MAX_POOL_SIZE = 10;

    // ── 격리 (인스턴스 단위, A-1·AD-2) ────────────────────────────────────────────
    /** 전용 측정 Redis 포트. 측정 스택 전체가 이 포트에 묶인다(본체 6379 무접촉). */
    public static final int CACHE_REDIS_PORT = 6380;
    /** 캐시 키 prefix — 6380 안에서 키스페이스 격리. */
    public static final String KEY_PREFIX = "cache:product:list:";
    /** 격리 datasource 정책(AD-2): 본체 MySQL 공유 — datasource url 에 이 포트가 있어야 한다. */
    public static final int DATASOURCE_MYSQL_PORT = 3306;

    private CachePins() {
    }
}
