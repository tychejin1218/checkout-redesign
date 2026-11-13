package com.example.checkout.order.service

import com.example.checkout.common.exception.ApiException
import com.example.checkout.common.type.ApiStatus
import com.example.checkout.domain.entity.CheckoutOrder
import com.example.checkout.domain.entity.OrderItem
import com.example.checkout.domain.entity.OrderItemId
import com.example.checkout.domain.entity.OrderStatus
import com.example.checkout.domain.repository.OrderRepository
import com.example.checkout.legacy.resource.ApplyStat
import com.example.checkout.legacy.resource.LegacyResourcePort
import com.example.checkout.legacy.resource.ReserveResult
import com.example.checkout.order.dto.OrderResult
import com.example.checkout.order.dto.PlaceOrderCommand
import com.example.checkout.order.dto.toResult
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * 주문 생성. 신규 DB 와 레거시 DB 에 걸친 쓰기를 세 번의 커밋으로 나눠 진행한다 (DESIGN.md 5.2).
 *
 * ```
 * ① [신규 DB]   주문을 PENDING 으로 기록
 * ② [레거시 DB] 반영 기록 + 재고·쿠폰·포인트 차감 (한 트랜잭션)
 * ③ [신규 DB]   주문을 RESERVED 또는 FAILED 로 변경
 * ```
 *
 * 이 메서드는 트랜잭션이 아니다. 어느 단계 사이에서 끊겨도 ①의 기록이 남아 있으므로
 * [OrderRecoveryService] 가 레거시의 반영 기록을 보고 결론을 낸다.
 */
@Service
class OrderService(
    private val orderQuoteService: OrderQuoteService,
    private val orderRepository: OrderRepository,
    private val orderWriter: OrderWriter,
    private val legacyResourcePort: LegacyResourcePort,
    private val orderStepObserver: OrderStepObserver,
    private val orderSupport: OrderSupport,
) {
    /** 같은 회원이 같은 멱등 키로 다시 요청하면 다시 실행하지 않고 기존 주문의 현재 상태를 돌려준다 */
    fun placeOrder(command: PlaceOrderCommand): OrderResult =
        findExisting(command)?.toResult() ?: placeNewOrder(command)

    private fun placeNewOrder(command: PlaceOrderCommand): OrderResult {
        val quote = orderQuoteService.quote(command)
        val (order, items) = recordPending(command, quote) ?: return existingResult(command)
        orderStepObserver.passed(OrderStep.PENDING_RECORDED, order.orderNo)

        val reserveResult = legacyResourcePort.reserve(order.toResources(items))
        orderStepObserver.passed(OrderStep.LEGACY_RESERVED, order.orderNo)

        settle(order.orderNo, reserveResult)
        return orderRepository.findByOrderNo(order.orderNo)?.toResult() ?: throw ApiException(ApiStatus.ORDER_NOT_FOUND)
    }

    /**
     * ① 주문을 PENDING 으로 기록한다.
     *
     * INSERT 가 제약 위반으로 실패하는 경우는 둘이다.
     * - 같은 회원·같은 멱등 키의 요청이 동시에 들어와 먼저 기록됐다 → null 을 돌려주고 그 주문을 쓴다.
     * - 주문번호가 다른 주문과 겹쳤다 → 새 번호로 다시 시도한다. 기존 주문은 바뀌지 않는다.
     */
    private fun recordPending(
        command: PlaceOrderCommand,
        quote: OrderQuote,
        attempt: Int = 1,
    ): Pair<CheckoutOrder, List<OrderItem>>? {
        val order = orderSupport.newOrder(command, quote)
        val items = orderSupport.newItems(order.orderNo, quote)
        return try {
            orderWriter.createPending(order, items)
            order to items
        } catch (ex: DataAccessException) {
            when {
                findExisting(command) != null -> null
                attempt < MAX_ORDER_NO_ATTEMPTS -> recordPending(command, quote, attempt + 1)
                else -> throw ex
            }
        }
    }

    private fun findExisting(command: PlaceOrderCommand): CheckoutOrder? =
        orderRepository.findByMemberNoAndIdempotencyKey(command.memberNo, command.idempotencyKey)

    private fun existingResult(command: PlaceOrderCommand): OrderResult =
        findExisting(command)?.toResult() ?: throw ApiException(ApiStatus.ORDER_NOT_FOUND)

    /** ③ 레거시의 결과를 신규 주문 상태에 반영한다. 복구 작업자가 먼저 반영했으면 전이는 일어나지 않는다 */
    private fun settle(
        orderNo: String,
        result: ReserveResult,
    ) {
        when (result) {
            ReserveResult.Reserved -> {
                orderWriter.transition(orderNo, OrderStatus.PENDING, OrderStatus.RESERVED)
            }

            is ReserveResult.Rejected -> {
                orderWriter.transition(orderNo, OrderStatus.PENDING, OrderStatus.FAILED, result.reason.name)
            }

            is ReserveResult.AlreadyDecided -> {
                orderSupport.settleByMarker(orderNo, result.stat)
            }
        }
    }

    companion object {
        /** 주문번호가 겹쳤을 때 새 번호로 다시 기록해 보는 횟수(첫 시도 포함) */
        private const val MAX_ORDER_NO_ATTEMPTS = 3
    }
}

/**
 * 주문 생성과 복구가 함께 쓰는 조각
 */
@Component
class OrderSupport(
    private val orderNoGenerator: OrderNoGenerator,
    private val orderWriter: OrderWriter,
    private val clock: Clock,
) {
    fun newOrder(
        command: PlaceOrderCommand,
        quote: OrderQuote,
    ): CheckoutOrder =
        CheckoutOrder(
            orderNo = orderNoGenerator.next(),
            memberNo = command.memberNo,
            itemAmount = quote.itemAmount,
            discountAmount = quote.discountAmount,
            payAmount = quote.payAmount,
            pointAmount = command.pointAmount,
            couponNo = command.couponNo,
            idempotencyKey = command.idempotencyKey,
            createdAt = clock.millis(),
        )

    fun newItems(
        orderNo: String,
        quote: OrderQuote,
    ): List<OrderItem> =
        quote.lines.mapIndexed { index, line ->
            OrderItem(OrderItemId(orderNo, index + 1), line.goodsCd, line.goodsName, line.qty, line.unitPrice)
        }

    /**
     * 레거시 반영 기록에 따라 PENDING 주문의 결론을 낸다.
     *
     * 반영 기록이 'A' 면 차감이 반영된 것이므로 RESERVED, 그 밖에는 반영되지 않았거나 이미 되돌린 것이므로 FAILED.
     */
    fun settleByMarker(
        orderNo: String,
        stat: ApplyStat,
    ): Boolean =
        if (stat == ApplyStat.APPLIED) {
            orderWriter.transition(orderNo, OrderStatus.PENDING, OrderStatus.RESERVED)
        } else {
            orderWriter.transition(orderNo, OrderStatus.PENDING, OrderStatus.FAILED, FAIL_REASON_NOT_APPLIED)
        }

    companion object {
        /** 레거시에 차감이 반영되지 않은 채 끊긴 주문 */
        const val FAIL_REASON_NOT_APPLIED = "NOT_APPLIED"
    }
}
