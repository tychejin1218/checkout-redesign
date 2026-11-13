package com.example.checkout.order.service

import org.springframework.stereotype.Component

/**
 * DB 간 쓰기 절차에서 단계 하나가 커밋된 직후의 지점 (DESIGN.md 5.2, 5.4)
 */
enum class OrderStep {
    /** ① 신규 DB 에 주문을 PENDING 으로 기록했다 */
    PENDING_RECORDED,

    /** ② 레거시 DB 에 자원 확보 결과가 확정됐다 */
    LEGACY_RESERVED,

    /** 신규 DB 에 주문을 CANCELING 으로 기록했다 */
    CANCELING_RECORDED,

    /** 레거시 DB 에 자원을 반환했다 */
    LEGACY_RELEASED,
}

/**
 * 단계 사이에 끼어드는 지점. 두 DB 의 커밋 사이에는 항상 틈이 있고 프로세스는 그 틈에서 죽을 수 있다.
 *
 * 운영에서는 아무것도 하지 않는다([NoOpOrderStepObserver]). 테스트가 이 지점에서 예외를 던져
 * "여기서 프로세스가 죽었다" 를 재현한다.
 */
fun interface OrderStepObserver {
    fun passed(
        step: OrderStep,
        orderNo: String,
    )
}

@Component
class NoOpOrderStepObserver : OrderStepObserver {
    override fun passed(
        step: OrderStep,
        orderNo: String,
    ) = Unit
}
