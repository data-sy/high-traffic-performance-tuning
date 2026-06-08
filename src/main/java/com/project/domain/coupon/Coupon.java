package com.project.domain.coupon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "coupon")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "total_qty", nullable = false)
    private Integer totalQuantity;

    @Column(nullable = false)
    private Integer issued = 0;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    public Coupon(String name, Integer totalQuantity) {
        this.name = name;
        this.totalQuantity = totalQuantity;
        this.issued = 0;
    }

    /**
     * 발급 카운터 증가. 한도 소진이면 {@link CouponSoldOutException}.
     * 이것은 락이 아니다 — check-then-act의 원자성(동시성)은 Service 전략의 책임이다.
     */
    public void issue() {
        if (this.issued >= this.totalQuantity) {
            throw new CouponSoldOutException(this.id);
        }
        this.issued++;
    }
}
