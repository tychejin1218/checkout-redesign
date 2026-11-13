package com.example.checkout.order

import com.example.checkout.domain.entity.OrderStatus
import com.example.checkout.domain.repository.OrderItemRepository
import com.example.checkout.domain.repository.OrderRepository
import com.example.checkout.legacy.resource.LegacyResourcePort
import com.example.checkout.order.dto.OrderLine
import com.example.checkout.order.dto.PlaceOrderCommand
import com.example.checkout.order.service.OrderCancelService
import com.example.checkout.order.service.OrderRecoveryService
import com.example.checkout.order.service.OrderService
import com.example.checkout.order.service.OrderStep
import com.example.checkout.order.service.toResources
import com.example.checkout.support.IntegrationTest
import com.example.checkout.support.SimulatedCrash
import com.example.checkout.support.runConcurrently
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration

/**
 * 여러 요청이 동시에 들어와도 레거시 자원이 정확히 맞는지 확인한다.
 *
 * SQLite 는 쓰기를 DB 파일 단위로 직렬화하므로 여기서 증명하는 것은 절차의 정확성이다(DESIGN.md 10장).
 */
class OrderConcurrencyTest(
    @Autowired private val orderService: OrderService,
    @Autowired private val orderCancelService: OrderCancelService,
    @Autowired private val orderRecoveryService: OrderRecoveryService,
    @Autowired private val legacyResourcePort: LegacyResourcePort,
    @Autowired private val orderRepository: OrderRepository,
    @Autowired private val orderItemRepository: OrderItemRepository,
) : IntegrationTest() {
    @Test
    fun `재고 3개인 상품에 20명이 동시에 주문하면 3명만 성공하고 재고는 0 이 된다`() {
        val results =
            runConcurrently(20) {
                orderService.placeOrder(orderCommand(G002, qty = 1, unitPrice = G002_PRICE))
            }.map { it.getOrThrow() }

        assertThat(results.count { it.status == OrderStatus.RESERVED }).isEqualTo(G002_STOCK)
        assertThat(results.filter { it.status == OrderStatus.FAILED })
            .hasSize(20 - G002_STOCK)
            .allMatch { it.failReason == "OUT_OF_STOCK" }
        assertThat(stockOf(G002)).isZero()
        assertThat(markerCount("A")).isEqualTo(G002_STOCK)
    }

    @Test
    fun `같은 멱등 키의 요청이 동시에 10번 들어와도 주문은 하나이고 재고는 한 번만 줄어든다`() {
        val command = orderCommand(G001, qty = 1, idempotencyKey = "key-double-click")

        val results = runConcurrently(10) { orderService.placeOrder(command) }.map { it.getOrThrow() }

        assertThat(results.map { it.orderNo }.distinct()).hasSize(1)
        assertThat(orderRepository.count()).isEqualTo(1)
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK - 1)
        assertThat(markerCount("A")).isEqualTo(1)
    }

    @Test
    fun `같은 쿠폰으로 동시에 5건을 주문해도 쿠폰은 한 주문에만 쓰인다`() {
        issueCoupon("CPN900", MEMBER_1002, discountAmount = 10_000)

        val results =
            runConcurrently(5) { index ->
                orderService.placeOrder(
                    PlaceOrderCommand(
                        memberNo = MEMBER_1002,
                        lines = listOf(OrderLine(G001, 1)),
                        expectedPayAmount = 18_000,
                        idempotencyKey = "key-coupon-$index",
                        couponNo = "CPN900",
                    ),
                )
            }

        // 나머지는 금액 계산에서 거절되거나(예외) 레거시 차감에서 거절된다(FAILED)
        val reserved = results.mapNotNull { it.getOrNull() }.filter { it.status == OrderStatus.RESERVED }
        assertThat(reserved).hasSize(1)
        assertThat(couponUseOf("CPN900")).isEqualTo("Y" to reserved.single().orderNo)
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK - 1)
    }

    @Test
    fun `같은 주문을 동시에 5번 취소해도 재고는 한 번만 돌아온다`() {
        val order = orderService.placeOrder(orderCommand(G001, qty = 2))

        val results = runConcurrently(5) { orderCancelService.cancel(order.orderNo) }

        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK)
        assertThat(markerOf(order.orderNo)).isEqualTo("R")
        assertThat(orderRepository.findByOrderNo(order.orderNo)?.status).isEqualTo(OrderStatus.CANCELED)
    }

    @Test
    fun `복구와 지연된 차감이 동시에 실행돼도 주문 상태와 재고는 같은 결론에 이른다`() {
        every { orderStepObserver.passed(OrderStep.PENDING_RECORDED, any()) } throws SimulatedCrash()
        var expectedStock = G001_STOCK

        repeat(10) { round ->
            // 주문 기록 직후 끊긴 주문을 만든다
            val key = "key-race-$round"
            runCatching { orderService.placeOrder(orderCommand(G001, qty = 1, idempotencyKey = key)) }
            val order = checkNotNull(orderRepository.findByMemberNoAndIdempotencyKey(MEMBER_1002, key))
            val resources = order.toResources(orderItemRepository.findByIdOrderNoOrderByIdSeq(order.orderNo))

            // 복구 작업자와, 죽은 줄 알았던 원래 요청의 레거시 차감이 동시에 실행된다
            runConcurrently(2) { index ->
                if (index ==
                    0
                ) {
                    orderRecoveryService.recoverStuckOrders(Duration.ZERO)
                } else {
                    legacyResourcePort.reserve(resources)
                }
            }.forEach { it.getOrThrow() }
            // 차감이 이긴 경우 복구가 이미 지나갔을 수 있으므로 한 번 더 돌린다
            orderRecoveryService.recoverStuckOrders(Duration.ZERO)

            val status = orderRepository.findByOrderNo(order.orderNo)?.status
            when (markerOf(order.orderNo)) {
                "A" -> {
                    expectedStock -= 1
                    assertThat(status).isEqualTo(OrderStatus.RESERVED)
                }

                "X" -> {
                    assertThat(status).isEqualTo(OrderStatus.FAILED)
                }

                else -> {
                    error("반영 기록이 A 도 X 도 아니다: ${markerOf(order.orderNo)}")
                }
            }
            assertThat(stockOf(G001)).isEqualTo(expectedStock)
        }
    }
}
