package com.project.domain.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    @Query(value = """
            SELECT oi.product_id, SUM(oi.quantity) as sales_count
            FROM order_item oi
            GROUP BY oi.product_id
            ORDER BY sales_count DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findTopProductsBySales(@Param("limit") int limit);
}
