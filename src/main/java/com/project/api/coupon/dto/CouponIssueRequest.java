package com.project.api.coupon.dto;

/**
 * 발급 요청. 하네스 페이로드 계약(coupon-test.js): {@code {"couponId": <long>, "userId": <long>}}.
 * 필드명 couponId/userId는 하네스와 글자 일치(역직렬화 키).
 */
public record CouponIssueRequest(Long couponId, Long userId) {
}
