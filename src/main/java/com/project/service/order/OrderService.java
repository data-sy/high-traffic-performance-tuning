package com.project.service.order;

import com.project.domain.order.Order;
import com.project.domain.order.OrderItem;
import com.project.domain.order.OrderRepository;
import com.project.domain.order.OrderStatus;
import com.project.domain.product.Product;
import com.project.domain.product.ProductRepository;
import com.project.service.ranking.RankingV4Service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final RankingV4Service rankingV4Service;

    @Transactional
    public Order createOrder(Long userId, List<OrderItemRequest> items) {
        // Calculate total and resolve products first
        int total = 0;
        List<OrderItem> orderItems = new ArrayList<>();
        for (OrderItemRequest itemReq : items) {
            Product product = productRepository.findById(itemReq.productId())
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + itemReq.productId()));

            orderItems.add(new OrderItem(product, itemReq.quantity(), product.getPrice()));
            total += product.getPrice() * itemReq.quantity();
        }

        Order order = new Order(userId, OrderStatus.PENDING, total);
        for (OrderItem orderItem : orderItems) {
            order.addOrderItem(orderItem);
        }

        Order saved = orderRepository.save(order);

        // Update Redis ranking for each ordered product
        for (OrderItem item : saved.getOrderItems()) {
            rankingV4Service.incrementScore(item.getProduct().getId(), item.getQuantity());
        }

        return saved;
    }

    public record OrderItemRequest(Long productId, int quantity) {}
}
