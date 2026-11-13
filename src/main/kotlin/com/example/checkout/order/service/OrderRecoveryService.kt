package com.example.checkout.order.service

import com.example.checkout.common.exception.ApiException
import com.example.checkout.domain.entity.CheckoutOrder
import com.example.checkout.domain.entity.OrderStatus
import com.example.checkout.domain.repository.OrderRepository
import com.example.checkout.legacy.resource.LegacyResourcePort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

/**
 * 두 DB 의 커밋 사이에서 끊겨 중간 상태에 머문 주문을 끝까지 진행하거나 되돌린다 (DESIGN.md 5.3).
 *
 * 판단 근거는 레거시의 반영 기록 하나다. 정상 흐름과 같은 멱등 연산을 쓰므로 정상 흐름과 겹치거나 여러 번 실행돼도 결과가 같다.
 */
@Service
class OrderRecoveryService(
    private val orderRepository: OrderRepository,
    private val legacyResourcePort: LegacyResourcePort,
    private val orderSupport: OrderSupport,
    private val orderCancelService: OrderCancelService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * PENDING·CANCELING 에 [stuckFor] 넘게 머문 주문을 복구한다.
     *
     * 주문 한 건의 복구가 예외로 끝나도 나머지 주문은 계속 복구한다.
     *
     * @return 복구한 주문 수. 예외로 끝난 주문은 세지 않는다
     */
    fun recoverStuckOrders(stuckFor: Duration): Int =
        findOlderThan(stuckFor, OrderStatus.PENDING, OrderStatus.CANCELING).count { order ->
            isolated("복구", order) {
                if (order.status ==
                    OrderStatus.PENDING
                ) {
                    recoverPending(order)
                } else {
                    orderCancelService.completeCancel(order)
                }
                true
            }
        }

    /**
     * RESERVED 에서 [unpaidFor] 넘게 결제되지 않은 주문을 되돌린다.
     *
     * 주문 한 건의 처리가 예외로 끝나도 나머지 주문은 계속 되돌린다.
     *
     * @return 되돌린 주문 수. 건너뛰었거나 예외로 끝난 주문은 세지 않는다
     */
    fun cancelUnpaidOrders(unpaidFor: Duration): Int =
        findOlderThan(unpaidFor, OrderStatus.RESERVED).count { order ->
            isolated("만료 처리", order) { cancelUnpaid(order) }
        }

    private fun cancelUnpaid(order: CheckoutOrder): Boolean =
        try {
            orderCancelService.cancel(order.orderNo)
            true
        } catch (ex: ApiException) {
            // 조회한 뒤에 결제가 끝났거나 다른 쪽이 먼저 취소했다
            log.info("만료 처리 건너뜀. orderNo={} reason={}", order.orderNo, ex.apiStatus)
            false
        }

    /**
     * 주문 한 건의 처리가 예외로 끝나도 다음 주문으로 넘어가게 한다.
     *
     * 실패한 주문은 중간 상태(PENDING·CANCELING) 그대로 남으므로 다음 실행에서 다시 대상이 된다.
     * DB 장애처럼 어떤 예외가 올지 알 수 없는 자리라 예외 종류를 가리지 않고 잡는다.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun isolated(
        task: String,
        order: CheckoutOrder,
        action: () -> Boolean,
    ): Boolean =
        try {
            action()
        } catch (ex: Exception) {
            log.error("{} 실패. 다음 주문으로 넘어간다. orderNo={} status={}", task, order.orderNo, order.status, ex)
            false
        }

    /**
     * PENDING 주문의 결론을 낸다.
     *
     * 반영 기록이 없으면 "반영 안 됨" 인데, 그렇게 결론 내린 직후에 지연되던 원래 요청의 차감이 도착할 수 있다.
     * 그래서 반영 기록을 읽기만 하지 않고 거부 기록을 먼저 넣는다. 원래 요청이 먼저 커밋했다면 그 반영 기록('A')이 돌아온다.
     */
    private fun recoverPending(order: CheckoutOrder) {
        val stat = legacyResourcePort.rejectIfUndecided(order.orderNo)
        val settled = orderSupport.settleByMarker(order.orderNo, stat)
        log.info("PENDING 주문 복구. orderNo={} marker={} settled={}", order.orderNo, stat, settled)
    }

    private fun findOlderThan(
        age: Duration,
        vararg statuses: OrderStatus,
    ): List<CheckoutOrder> =
        orderRepository.findByStatusInAndUpdatedAtLessThanEqualOrderByUpdatedAt(
            statuses.toList(),
            clock.millis() - age.toMillis(),
        )
}
