package com.project.api.coupon.dto;

/**
 * 실패 응답. <b>하네스 계약(추출 고정):</b> k6가 503에서 {@code res.json('errorCode')}를
 * <b>top-level 키</b>로 읽어 ⓐ/ⓑ/ⓒ 서브분류한다. 따라서 errorCode는 반드시 루트 필드여야 하고,
 * 503 응답은 항상 valid JSON 바디를 동반해야 한다(비-JSON이면 k6가 throw→미분류로 샘).
 *
 * <p>409(한도소진)는 status 자체가 계약(하네스가 errorCode 안 읽음) — 여기 errorCode는 사람용 가독성.
 */
public record CouponErrorResponse(String errorCode, String message) {

    public static CouponErrorResponse of(String errorCode, String message) {
        return new CouponErrorResponse(errorCode, message);
    }
}
