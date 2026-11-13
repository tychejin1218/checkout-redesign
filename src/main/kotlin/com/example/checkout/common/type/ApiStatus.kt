package com.example.checkout.common.type

import org.springframework.http.HttpStatus

/**
 * API 응답 코드
 *
 * 응답 본문의 `statusCode` 에는 enum 이름이 그대로 들어간다([code]).
 *
 * 명명 규칙:
 * - 성공은 `OK` 하나뿐이다.
 * - 도메인 접두: 도메인별 코드는 `CART_*`, `ORDER_*`, `PAYMENT_*` 처럼 도메인 이름을 접두로 붙인다.
 * - 공통 접두: 도메인과 무관한 사유는 `VALIDATION_*`, `RESOURCE_*`, `SYSTEM_*`.
 * - 접미: `*_NOT_FOUND`, `*_INVALID`, `*_DUPLICATED`, `*_FORBIDDEN`, `*_EXPIRED`, `*_LIMIT_EXCEEDED`, `*_FAILED`.
 * - 코드 하나는 항상 같은 HTTP 상태([httpStatus])로 나간다. 예외를 던지는 곳에서는 HTTP 상태를 지정하지 않는다.
 */
enum class ApiStatus(
    val httpStatus: HttpStatus,
    val message: String,
) {
    // 성공
    OK(HttpStatus.OK, "성공"),

    // 공통 (도메인 무관)
    SYSTEM_CUSTOM_EXCEPTION(HttpStatus.BAD_REQUEST, "오류가 발생했습니다. 확인 후 다시 시도해주세요."),
    VALIDATION_INVALID_REQUEST(HttpStatus.BAD_REQUEST, "유효하지 않은 요청입니다."),
    RESOURCE_DUPLICATED(HttpStatus.CONFLICT, "중복된 요청입니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 정보입니다."),

    // 주문
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 주문입니다."),
    ORDER_MEMBER_UNAVAILABLE(HttpStatus.FORBIDDEN, "주문할 수 없는 회원입니다."),
    ORDER_GOODS_UNAVAILABLE(HttpStatus.CONFLICT, "판매하지 않는 상품이 포함되어 있습니다."),
    ORDER_COUPON_UNAVAILABLE(HttpStatus.CONFLICT, "사용할 수 없는 쿠폰입니다."),
    ORDER_POINT_INVALID(HttpStatus.BAD_REQUEST, "사용할 포인트 금액이 올바르지 않습니다."),
    ORDER_AMOUNT_CHANGED(HttpStatus.CONFLICT, "결제 금액이 변경되었습니다. 금액을 다시 확인해주세요."),
    ORDER_STATUS_INVALID(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없는 주문입니다."),

    // 시스템 (ExceptionAdvice 가 프레임워크 예외를 자동 매핑)
    SYSTEM_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "내부 오류가 발생했습니다."),
    VALIDATION_METHOD_ARGUMENT_INVALID(HttpStatus.BAD_REQUEST, "파라미터가 유효하지 않습니다."),
    VALIDATION_PARAMETER_MISSING(HttpStatus.BAD_REQUEST, "필수 파라미터가 누락되었습니다."),
    VALIDATION_CONSTRAINT_VIOLATED(HttpStatus.BAD_REQUEST, "파라미터 유효성 검사에 실패했습니다."),
    VALIDATION_PARAMETER_TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "파라미터 타입이 올바르지 않습니다."),
    VALIDATION_MESSAGE_NOT_READABLE(HttpStatus.BAD_REQUEST, "읽을 수 있는 요청 정보가 없습니다."),
    SYSTEM_NO_HANDLER_FOUND(HttpStatus.NOT_FOUND, "요청한 URL을 찾을 수 없습니다."),
    SYSTEM_METHOD_NOT_SUPPORTED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 메서드입니다."),
    SYSTEM_MEDIA_TYPE_NOT_SUPPORTED(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원되지 않는 미디어 타입입니다."),
    ;

    /** 응답 본문의 statusCode (enum 이름) */
    val code: String
        get() = name
}
