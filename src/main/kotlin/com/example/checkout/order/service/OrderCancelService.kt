package com.example.checkout.order.service

import com.example.checkout.common.exception.ApiException
import com.example.checkout.common.type.ApiStatus
import com.example.checkout.domain.entity.CheckoutOrder
import com.example.checkout.domain.entity.OrderStatus
import com.example.checkout.domain.repository.OrderItemRepository
import com.example.checkout.domain.repository.OrderRepository
import com.example.checkout.legacy.resource.LegacyResourcePort
import com.example.checkout.legacy.resource.ReleaseResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 확보한 자원을 되돌린다. 결제 실패나 미결제 만료에서 부른다 (DESIGN.md 5.4).
 *
 * ```
 * 1. [신규 DB]   주문을 CANCELING 으로 변경
 * 2. [레거시 DB] 반영 기록 'A' → 'R' + 재고·쿠폰·포인트 반환 (한 트랜잭션)
 * 3. [신규 DB]   주문을 CANCELED 로 변경
 * ```
 */
@Service
class OrderCancelService(
    private val orderRepository: OrderRepository,
    private val orderItemRepository: OrderItemRepository,
    private val orderWriter: OrderWriter,
    private val legacyResourcePort: LegacyResourcePort,
    private val orderStepObserver: OrderStepObserver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun cancel(orderNo: String) {
        val order = orderRepository.findByOrderNo(orderNo) ?: throw ApiException(ApiStatus.ORDER_NOT_FOUND)
        // RESERVED 인 주문만 되돌릴 수 있다. 동시에 들어온 취소는 한쪽만 통과한다
        if (!orderWriter.transition(orderNo, OrderStatus.RESERVED, OrderStatus.CANCELING)) {
            throw ApiException(ApiStatus.ORDER_STATUS_INVALID)
        }
        orderStepObserver.passed(OrderStep.CANCELING_RECORDED, orderNo)
        completeCancel(order)
    }

    /** CANCELING 인 주문의 2, 3단계. 몇 번을 다시 실행해도 자원은 한 번만 반환된다 */
    fun completeCancel(order: CheckoutOrder) {
        val items = orderItemRepository.findByIdOrderNoOrderByIdSeq(order.orderNo)
        val result = legacyResourcePort.release(order.toResources(items))
        if (result == ReleaseResult.NOT_APPLIED) {
            log.warn("차감된 적이 없는 주문을 반환하려 했습니다. orderNo={}", order.orderNo)
        }
        orderStepObserver.passed(OrderStep.LEGACY_RELEASED, order.orderNo)
        orderWriter.transition(order.orderNo, OrderStatus.CANCELING, OrderStatus.CANCELED)
    }
}
