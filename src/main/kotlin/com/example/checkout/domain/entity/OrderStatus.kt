package com.example.checkout.domain.entity

/**
 * 주문 상태 (DESIGN.md 3.1)
 *
 * [PENDING] 과 [CANCELING] 은 "DB 간 작업이 진행 중" 인 중간 상태다. 여기에 오래 머문 주문이 복구 대상이다.
 */
enum class OrderStatus {
    /** 주문 의도를 기록했다. 레거시 자원 확보 결과를 아직 모른다 */
    PENDING,

    /** 레거시 자원(재고·쿠폰·포인트)을 확보했다. 결제 진행 중 */
    RESERVED,

    /** 결제가 끝났다 */
    PAID,

    /** 자원 확보에 실패했다. 레거시에는 아무것도 반영되지 않았다 */
    FAILED,

    /** 결제 실패·만료로 되돌리기로 했다. 레거시 자원 반환 결과를 아직 모른다 */
    CANCELING,

    /** 레거시 자원 반환이 끝났다 */
    CANCELED,
}
