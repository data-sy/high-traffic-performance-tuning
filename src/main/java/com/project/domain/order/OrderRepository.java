package com.project.domain.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * v1~v3 목록 경로의 헤더 쿼리(무 fetch). 반환된 Order의 orderItems/product는 LAZY 미적재 상태라,
     * 서비스 루프에서 접근할 때 secondary SELECT가 발생한다(N+1 재현/배치 토글 대상).
     */
    List<Order> findByUserId(Long userId);
}
