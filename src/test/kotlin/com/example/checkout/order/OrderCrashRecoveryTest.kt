package com.example.checkout.order

import com.example.checkout.domain.entity.CheckoutOrder
import com.example.checkout.domain.entity.OrderStatus
import com.example.checkout.domain.repository.OrderItemRepository
import com.example.checkout.domain.repository.OrderRepository
import com.example.checkout.legacy.resource.ApplyStat
import com.example.checkout.legacy.resource.LegacyResourcePort
import com.example.checkout.legacy.resource.ReserveResult
import com.example.checkout.order.service.OrderCancelService
import com.example.checkout.order.service.OrderRecoveryService
import com.example.checkout.order.service.OrderService
import com.example.checkout.order.service.OrderStep
import com.example.checkout.order.service.toResources
import com.example.checkout.support.IntegrationTest
import com.example.checkout.support.SimulatedCrash
import io.mockk.every
import io.mockk.justRun
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration

/**
 * 두 DB 의 커밋 사이에서 프로세스가 죽었을 때, 복구를 돌리면 신규 주문과 레거시 자원이 같은 결론으로 수렴하는지 확인한다.
 *
 * DESIGN.md 5.3 의 표를 한 줄씩 재현한다.
 */
class OrderCrashRecoveryTest(
    @Autowired private val orderService: OrderService,
    @Autowired private val orderCancelService: OrderCancelService,
    @Autowired private val orderRecoveryService: OrderRecoveryService,
    @Autowired private val legacyResourcePort: LegacyResourcePort,
    @Autowired private val orderRepository: OrderRepository,
    @Autowired private val orderItemRepository: OrderItemRepository,
) : IntegrationTest() {
    @Test
    fun `주문 기록 뒤 레거시 차감 전에 죽으면 주문만 PENDING 으로 남고 복구가 FAILED 로 끝낸다`() {
        val order = placeOrderAndCrashAt(OrderStep.PENDING_RECORDED)
        assertThat(order.status).isEqualTo(OrderStatus.PENDING)
        assertThat(markerOf(order.orderNo)).isNull()
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK)

        orderRecoveryService.recoverStuckOrders(Duration.ZERO)

        assertThat(reload(order).status).isEqualTo(OrderStatus.FAILED)
        assertThat(reload(order).failReason).isEqualTo("NOT_APPLIED")
        assertThat(markerOf(order.orderNo)).isEqualTo("X")
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK)
    }

    @Test
    fun `복구가 실패로 끝낸 주문의 차감이 뒤늦게 도착해도 재고는 줄지 않는다`() {
        val order = placeOrderAndCrashAt(OrderStep.PENDING_RECORDED)
        orderRecoveryService.recoverStuckOrders(Duration.ZERO)

        // 죽은 줄 알았던 원래 요청이 살아 있다가 레거시 차감을 시도한다
        val lateResult = legacyResourcePort.reserve(resourcesOf(order))

        assertThat(lateResult).isEqualTo(ReserveResult.AlreadyDecided(ApplyStat.REJECTED))
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK)
        assertThat(reload(order).status).isEqualTo(OrderStatus.FAILED)
    }

    @Test
    fun `레거시 차감 뒤 주문 상태 반영 전에 죽으면 재고만 줄어 있고 복구가 RESERVED 로 진행시킨다`() {
        val order = placeOrderAndCrashAt(OrderStep.LEGACY_RESERVED)
        assertThat(order.status).isEqualTo(OrderStatus.PENDING)
        assertThat(markerOf(order.orderNo)).isEqualTo("A")
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK - 2)

        orderRecoveryService.recoverStuckOrders(Duration.ZERO)

        assertThat(reload(order).status).isEqualTo(OrderStatus.RESERVED)
        assertThat(markerOf(order.orderNo)).isEqualTo("A")
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK - 2)
    }

    @Test
    fun `복구를 여러 번 돌려도 결과가 같다`() {
        val notApplied = placeOrderAndCrashAt(OrderStep.PENDING_RECORDED, idempotencyKey = "key-1")
        val applied = placeOrderAndCrashAt(OrderStep.LEGACY_RESERVED, idempotencyKey = "key-2")

        val firstRun = orderRecoveryService.recoverStuckOrders(Duration.ZERO)
        val secondRun = orderRecoveryService.recoverStuckOrders(Duration.ZERO)

        assertThat(firstRun).isEqualTo(2)
        assertThat(secondRun).isZero()
        assertThat(reload(notApplied).status).isEqualTo(OrderStatus.FAILED)
        assertThat(reload(applied).status).isEqualTo(OrderStatus.RESERVED)
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK - 2)
    }

    @Test
    fun `처리 중일 수 있는 최근 주문은 복구가 건드리지 않는다`() {
        val order = placeOrderAndCrashAt(OrderStep.PENDING_RECORDED)

        val recovered = orderRecoveryService.recoverStuckOrders(Duration.ofMinutes(1))

        assertThat(recovered).isZero()
        assertThat(reload(order).status).isEqualTo(OrderStatus.PENDING)
        assertThat(markerOf(order.orderNo)).isNull()
    }

    @Test
    fun `끊긴 주문을 같은 멱등 키로 다시 요청하면 새로 실행하지 않고 현재 상태를 돌려준다`() {
        val order = placeOrderAndCrashAt(OrderStep.LEGACY_RESERVED, idempotencyKey = "key-retry")

        val retried = orderService.placeOrder(orderCommand(G001, qty = 2, idempotencyKey = "key-retry"))

        assertThat(retried.orderNo).isEqualTo(order.orderNo)
        assertThat(retried.status).isEqualTo(OrderStatus.PENDING)
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK - 2)
    }

    @Test
    fun `취소 기록 뒤 레거시 반환 전에 죽으면 복구가 자원을 돌려주고 CANCELED 로 끝낸다`() {
        val order = reserveThenCancelAndCrashAt(OrderStep.CANCELING_RECORDED)
        assertThat(order.status).isEqualTo(OrderStatus.CANCELING)
        assertThat(markerOf(order.orderNo)).isEqualTo("A")
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK - 2)

        orderRecoveryService.recoverStuckOrders(Duration.ZERO)

        assertThat(reload(order).status).isEqualTo(OrderStatus.CANCELED)
        assertThat(markerOf(order.orderNo)).isEqualTo("R")
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK)
    }

    @Test
    fun `레거시 반환 뒤 주문 상태 반영 전에 죽으면 복구가 다시 반환하지 않고 CANCELED 로 끝낸다`() {
        val order = reserveThenCancelAndCrashAt(OrderStep.LEGACY_RELEASED)
        assertThat(order.status).isEqualTo(OrderStatus.CANCELING)
        assertThat(markerOf(order.orderNo)).isEqualTo("R")
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK)

        orderRecoveryService.recoverStuckOrders(Duration.ZERO)

        assertThat(reload(order).status).isEqualTo(OrderStatus.CANCELED)
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK)
    }

    /** G001 2개 주문을 시작하고 [step] 직후에 죽인다. 신규 DB 에 남은 주문을 돌려준다 */
    private fun placeOrderAndCrashAt(
        step: OrderStep,
        idempotencyKey: String = "key-crash",
    ): CheckoutOrder {
        every { orderStepObserver.passed(step, any()) } throws SimulatedCrash()
        assertThatThrownBy { orderService.placeOrder(orderCommand(G001, qty = 2, idempotencyKey = idempotencyKey)) }
            .isInstanceOf(SimulatedCrash::class.java)
        // 이후의 호출은 다시 정상 동작한다
        justRun { orderStepObserver.passed(any(), any()) }
        return checkNotNull(orderRepository.findByMemberNoAndIdempotencyKey(MEMBER_1002, idempotencyKey))
    }

    /** G001 2개를 정상 주문한 뒤 취소를 시작하고 [step] 직후에 죽인다 */
    private fun reserveThenCancelAndCrashAt(step: OrderStep): CheckoutOrder {
        val reserved = orderService.placeOrder(orderCommand(G001, qty = 2))
        every { orderStepObserver.passed(step, any()) } throws SimulatedCrash()
        assertThatThrownBy { orderCancelService.cancel(reserved.orderNo) }.isInstanceOf(SimulatedCrash::class.java)
        justRun { orderStepObserver.passed(any(), any()) }
        return checkNotNull(orderRepository.findByOrderNo(reserved.orderNo))
    }

    private fun reload(order: CheckoutOrder): CheckoutOrder = checkNotNull(orderRepository.findByOrderNo(order.orderNo))

    private fun resourcesOf(order: CheckoutOrder) =
        order.toResources(orderItemRepository.findByIdOrderNoOrderByIdSeq(order.orderNo))
}
