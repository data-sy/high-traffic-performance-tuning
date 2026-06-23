package com.project.service.order;

import com.project.api.order.dto.OrderDetailResponse;
import com.project.api.order.dto.OrderSummaryResponse;
import com.project.domain.order.Order;
import com.project.domain.order.OrderItem;
import com.project.domain.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * v2 — 정통 엔티티 EAGER(`@ManyToOne(fetch=EAGER)` / `@OneToMany(fetch=EAGER)`). {@code @EntityGraph 아님}(R1·B1).
 *
 * <p><b>코드 경로는 v1과 동일</b>(findByUserId 파생쿼리 + 루프 접근). 유일한 차이는 <b>측정 런에서만 토글되는
 * 전역 변수 = 엔티티 fetch 매핑</b>이다. 커밋 baseline은 LAZY를 유지하고, v2 측정 런에서만 EAGER로 토글한 뒤
 * 원복한다(v1 무오염은 측정 직전 런타임 assert로 검증). 별도 엔드포인트로 공존시키는 이유는 그 토글 효과를
 * 독립적으로 측정/대조하기 위함이다.
 *
 * <p>EAGER 토글 시 기대 거동(쿼리 수는 실측으로 확정 — a priori 단정 금지):
 * <ul>
 *   <li><b>목록</b>(파생 JPQL): 헤더 실행 후 각 Order의 EAGER orderItems를 secondary SELECT N회로 채움
 *       → <b>EAGER로도 N+1 잔존</b>. 단 EAGER {@code @ManyToOne product}(기본 style=JOIN)가 각 컬렉션
 *       SELECT에 조인 흡수 → 대표상품은 별도 SELECT 아님(∴ v1의 1+2N과 달리 ≈1+N, 구조가 다름).</li>
 *   <li><b>상세</b>({@code findById}={@code em.find} by PK): find()는 EAGER를 즉시 조인/로딩으로
 *       흡수 → N+1 미발생(~2회). ∴ v2의 N+1 시연은 <b>목록 경로로 한정</b>.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OrderQueryServiceV2 {

    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public List<OrderSummaryResponse> getOrders(Long userId) {
        List<Order> orders = orderRepository.findByUserId(userId);
        return orders.stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderDetailResponse getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        return OrderDetailResponse.from(order);
    }

    private OrderSummaryResponse toSummary(Order order) {
        List<OrderItem> items = order.getOrderItems();
        int itemCount = items.size();
        String firstProductName = items.stream()
                .min(Comparator.comparing(OrderItem::getId))
                .map(item -> item.getProduct().getName())
                .orElse(null);
        return new OrderSummaryResponse(
                order.getId(), order.getStatus(), order.getTotal(),
                order.getCreatedAt(), itemCount, firstProductName);
    }
}
