package com.project.domain.coupon;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    /**
     * v3 (SELECT ... FOR UPDATE) 전용 — 비관적 행 락.
     * 반드시 {@code @Transactional} 안에서 호출(락은 커밋 시 해제).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where c.id = :id")
    Optional<Coupon> findByIdForUpdate(@Param("id") Long id);

    /**
     * phase 3-9 R3/R4 전용 — 원자 조건부 발급(read-modify-write 1문 합치기).
     * <p>bulk {@code @Modifying}이라 <b>영속성 컨텍스트를 우회</b>한다: 엔티티를 managed로 적재하지 않으므로
     * 커밋 flush의 dirty {@code UPDATE coupon}이 소멸(R0 4문 → R3 3문). UPDATE 자체가 InnoDB X-락을 잡아
     * 직렬화 지점을 유지한다. affected=1이면 발급 성공, 0이면 소진(issued>=totalQuantity).
     * <p><b>@Version 비관리 주의(R5 격리 근거):</b> bulk update는 JPA 명세상 낙관락 버전 자동 증가·검사 밖이다.
     */
    @Modifying
    @Query("update Coupon c set c.issued = c.issued + 1 where c.id = :id and c.issued < c.totalQuantity")
    int tryConsume(@Param("id") Long id);   // affected: 1=성공 / 0=소진
}
