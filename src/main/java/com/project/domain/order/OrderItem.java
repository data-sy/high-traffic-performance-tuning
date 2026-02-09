package com.project.domain.order;

import com.project.domain.product.Product;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "order_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "order_id",
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT)  // 추가
    )
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "product_id",
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT)  // 추가
    )
    private Product product;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false)
    private Integer price;

    public OrderItem(Product product, Integer quantity, Integer price) {
        this.product = product;
        this.quantity = quantity;
        this.price = price;
    }

    void setOrder(Order order) {
        this.order = order;
    }
}
