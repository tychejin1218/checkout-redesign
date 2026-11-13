package com.example.checkout.order

import com.example.checkout.common.exception.ApiException
import com.example.checkout.common.type.ApiStatus
import com.example.checkout.domain.entity.OrderStatus
import com.example.checkout.domain.repository.OrderRepository
import com.example.checkout.order.dto.OrderLine
import com.example.checkout.order.dto.PlaceOrderCommand
import com.example.checkout.order.service.OrderCancelService
import com.example.checkout.order.service.OrderRecoveryService
import com.example.checkout.order.service.OrderService
import com.example.checkout.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration

/**
 * 자원 반환: 결제 실패·만료로 주문을 되돌릴 때 레거시의 재고·쿠폰·포인트가 정확히 한 번 돌아오는지 확인한다
 */
class OrderCancelTest(
    @Autowired private val orderService: OrderService,
    @Autowired private val orderCancelService: OrderCancelService,
    @Autowired private val orderRecoveryService: OrderRecoveryService,
    @Autowired private val orderRepository: OrderRepository,
) : IntegrationTest() {
    @Test
    fun `취소하면 재고와 쿠폰과 포인트가 돌아오고 반영 기록이 R 이 된다`() {
        issueCoupon("CPN900", MEMBER_1002, discountAmount = 10_000)
        val order =
            orderService.placeOrder(
                PlaceOrderCommand(
                    memberNo = MEMBER_1002,
                    lines = listOf(OrderLine(G001, 2)),
                    expectedPayAmount = 46_000,
                    idempotencyKey = "key-cancel",
                    couponNo = "CPN900",
                    pointAmount = 6_000,
                ),
            )

        orderCancelService.cancel(order.orderNo)

        assertThat(statusOf(order.orderNo)).isEqualTo(OrderStatus.CANCELED)
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK)
        assertThat(couponUseOf("CPN900")).isEqualTo("N" to null)
        assertThat(pointOf(MEMBER_1002)).isEqualTo(MEMBER_1002_POINT)
        assertThat(markerOf(order.orderNo)).isEqualTo("R")
    }

    @Test
    fun `이미 취소한 주문을 다시 취소하면 거절되고 재고는 한 번만 돌아온다`() {
        val order = orderService.placeOrder(orderCommand(G001, qty = 2))
        orderCancelService.cancel(order.orderNo)

        assertThatThrownBy { orderCancelService.cancel(order.orderNo) }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertThat(it.apiStatus).isEqualTo(ApiStatus.ORDER_STATUS_INVALID)
            }
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK)
    }

    @Test
    fun `자원 확보에 실패한 주문은 취소할 수 없다`() {
        val failed = orderService.placeOrder(orderCommand(G002, qty = G002_STOCK + 1, unitPrice = G002_PRICE))

        assertThatThrownBy { orderCancelService.cancel(failed.orderNo) }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertThat(it.apiStatus).isEqualTo(ApiStatus.ORDER_STATUS_INVALID)
            }
        assertThat(stockOf(G002)).isEqualTo(G002_STOCK)
    }

    @Test
    fun `결제되지 않은 채 제한 시간이 지난 주문은 되돌린다`() {
        val order = orderService.placeOrder(orderCommand(G001, qty = 2))

        val canceled = orderRecoveryService.cancelUnpaidOrders(Duration.ZERO)

        assertThat(canceled).isEqualTo(1)
        assertThat(statusOf(order.orderNo)).isEqualTo(OrderStatus.CANCELED)
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK)
    }

    @Test
    fun `제한 시간이 지나지 않은 주문은 되돌리지 않는다`() {
        val order = orderService.placeOrder(orderCommand(G001, qty = 2))

        val canceled = orderRecoveryService.cancelUnpaidOrders(Duration.ofMinutes(5))

        assertThat(canceled).isZero()
        assertThat(statusOf(order.orderNo)).isEqualTo(OrderStatus.RESERVED)
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK - 2)
    }

    private fun statusOf(orderNo: String): OrderStatus? = orderRepository.findByOrderNo(orderNo)?.status
}
