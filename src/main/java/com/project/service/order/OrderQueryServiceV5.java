package com.project.service.order;

import com.project.api.order.dto.OrderDetailResponse;
import com.project.api.order.dto.OrderItemResponse;
import com.project.api.order.dto.OrderSummaryResponse;
import com.project.domain.order.OrderRepository;
import com.project.domain.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v5 — DTO Projection(JPQL 생성자 표현식, QueryDSL 미사용). 필요 칸만 투영 → 영속성 컨텍스트 적재 0.
 *
 * <p>쿼리 최소화(축①)를 한 칸 양보하는 대신 <b>전송량(축②)·적재비용(축③)</b>을 깎는다 — 조회 전용·대용량.
 * <ul>
 *   <li><b>상세</b>: 헤더 1회 + 라인 1회 = <b>2회 고정</b>(엔티티 미적재).</li>
 *   <li><b>목록</b>(R-Q5 확정): 헤더 투영 1회 + 라인 요약 IN 투영 1회 = 2회. 라인 요약 쿼리는 product 조인으로
 *       (orderId, itemId, productName)을 투영, 서비스에서 orderId로 묶어 itemCount=그룹 크기,
 *       firstProductName=itemId 오름차순 첫 항목으로 집계(엔티티 미적재, v3식 IN 2단계와 동형).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OrderQueryServiceV5 {

    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public List<OrderSummaryResponse> getOrders(Long userId) {
        List<Object[]> headers = orderRepository.findListHeaders(userId);
        if (headers.isEmpty()) {
            return List.of();
        }
        List<Long> orderIds = headers.stream().map(h -> (Long) h[0]).toList();
        List<Object[]> lineRows = orderRepository.findListLineSummaries(orderIds);

        // orderId로 묶어 요약 집계. 라인은 (order.id asc, oi.id asc) 정렬돼 오므로
        // 각 주문의 첫 등장 productName이 대표상품(min itemId)이다.
        Map<Long, Integer> countByOrder = new HashMap<>();
        Map<Long, String> firstNameByOrder = new HashMap<>();
        for (Object[] row : lineRows) {
            Long orderId = (Long) row[0];
            String productName = (String) row[2];
            countByOrder.merge(orderId, 1, Integer::sum);
            firstNameByOrder.putIfAbsent(orderId, productName);
        }

        return headers.stream()
                .map(h -> new OrderSummaryResponse(
                        (Long) h[0],
                        (OrderStatus) h[1],
                        (Integer) h[2],
                        (LocalDateTime) h[3],
                        countByOrder.getOrDefault((Long) h[0], 0),
                        firstNameByOrder.get((Long) h[0])))
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderDetailResponse getOrder(Long orderId) {
        OrderDetailResponse header = orderRepository.findDetailHeader(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        List<OrderItemResponse> lines = orderRepository.findDetailLines(orderId);
        header.attachItems(lines);
        return header;
    }
}
