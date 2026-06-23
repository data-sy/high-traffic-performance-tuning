package com.project.domain.order;

import com.project.api.order.dto.OrderDetailResponse;
import com.project.api.order.dto.OrderItemResponse;
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

    // ── v5: DTO Projection (JPQL 생성자 표현식, QueryDSL 미사용) ───────────────
    // 필요 칸만 투영 → 엔티티 영속성 적재 0. 상세 = 헤더 1 + 라인 1 = 2회 고정.

    /** v5 상세 헤더 — 주문 헤더만 투영(아이템 미적재). */
    @Query("select new com.project.api.order.dto.OrderDetailResponse(o.id, o.status, o.total, o.createdAt) "
            + "from Order o where o.id = :orderId")
    Optional<OrderDetailResponse> findDetailHeader(@Param("orderId") Long orderId);

    /** v5 상세 라인 — 라인을 product 조인으로 한 번에 투영(엔티티 미적재). */
    @Query("select new com.project.api.order.dto.OrderItemResponse(oi.product.id, oi.product.name, oi.price, oi.quantity) "
            + "from OrderItem oi where oi.order.id = :orderId order by oi.id")
    List<OrderItemResponse> findDetailLines(@Param("orderId") Long orderId);

    /** v5 목록 헤더 — 주문 헤더만 투영(요약값은 별도 라인 쿼리로). */
    @Query("select o.id, o.status, o.total, o.createdAt from Order o where o.userId = :userId order by o.id")
    List<Object[]> findListHeaders(@Param("userId") Long userId);

    /**
     * v5 목록 요약 — 주어진 주문들의 (orderId, itemId, productName)을 product 조인으로 한 번에 투영.
     * 서비스에서 orderId로 묶어 itemCount = 그룹 크기, firstProductName = itemId 오름차순 첫 항목으로 집계.
     */
    @Query("select oi.order.id, oi.id, oi.product.name "
            + "from OrderItem oi where oi.order.id in :orderIds order by oi.order.id, oi.id")
    List<Object[]> findListLineSummaries(@Param("orderIds") List<Long> orderIds);
}
