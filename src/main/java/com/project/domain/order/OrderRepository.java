package com.project.domain.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * v1~v3 목록 경로의 헤더 쿼리(무 fetch). 반환된 Order의 orderItems/product는 LAZY 미적재 상태라,
     * 서비스 루프에서 접근할 때 secondary SELECT가 발생한다(N+1 재현/배치 토글 대상).
     */
    List<Order> findByUserId(Long userId);

    // ── v4: Fetch Join ──────────────────────────────────────────────────────
    // 컬렉션 1(orderItems) + to-one 1(product) → distinct로 카테시안 곱 통제, 단일 쿼리.
    // 컬렉션이 orderItems 하나뿐이라 MultipleBagFetchException은 이 스키마에서 재현 불가(이론 노트).

    /** v4 상세 — 단건 fetch join. 쿼리 1회로 주문+아이템+상품을 적재. */
    @Query("select distinct o from Order o "
            + "join fetch o.orderItems oi "
            + "join fetch oi.product "
            + "where o.id = :orderId")
    Optional<Order> findDetailByIdFetch(@Param("orderId") Long orderId);

    /**
     * v4 목록 — 비페이징 fetch join(단일 쿼리). 1:N fetch join + 실제 페이징(Pageable)은 메모리 페이징
     * 경고(HHH000104)를 유발하므로 페이징 미적용. 페이징 변종은 Step F에서 별도 측정/원리 설명.
     */
    @Query("select distinct o from Order o "
            + "join fetch o.orderItems oi "
            + "join fetch oi.product "
            + "where o.userId = :userId")
    List<Order> findByUserIdFetch(@Param("userId") Long userId);
}
