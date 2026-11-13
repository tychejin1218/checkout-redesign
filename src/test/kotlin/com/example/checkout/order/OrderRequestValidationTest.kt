package com.example.checkout.order

import com.example.checkout.common.exception.ApiException
import com.example.checkout.common.type.ApiStatus
import com.example.checkout.domain.repository.OrderRepository
import com.example.checkout.order.dto.OrderLine
import com.example.checkout.order.dto.PlaceOrderCommand
import com.example.checkout.order.service.OrderService
import com.example.checkout.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 주문을 기록하기 전의 요청 검증: 거절된 요청은 신규 DB 에 주문을 남기지 않고 레거시 자원도 바꾸지 않는다
 */
class OrderRequestValidationTest(
    @Autowired private val orderService: OrderService,
    @Autowired private val orderRepository: OrderRepository,
) : IntegrationTest() {
    @Test
    fun `품목이 없는 주문은 받지 않는다`() {
        val command = orderCommand().copy(lines = emptyList(), expectedPayAmount = 0)

        assertRejected(command, ApiStatus.VALIDATION_INVALID_REQUEST)
    }

    @Test
    fun `수량이 0 이하인 품목이 있는 주문은 받지 않는다`() {
        assertRejected(orderCommand(G001, qty = 0), ApiStatus.VALIDATION_INVALID_REQUEST)
        assertRejected(orderCommand(G001, qty = -1), ApiStatus.VALIDATION_INVALID_REQUEST)
    }

    @Test
    fun `같은 상품이 두 번 들어 있는 주문은 받지 않는다`() {
        val command =
            orderCommand().copy(
                lines = listOf(OrderLine(G001, 1), OrderLine(G001, 2)),
                expectedPayAmount = G001_PRICE * 3,
            )

        assertRejected(command, ApiStatus.VALIDATION_INVALID_REQUEST)
    }

    @Test
    fun `다른 회원의 쿠폰으로는 주문을 받지 않는다`() {
        issueCoupon("CPN910", MEMBER_1001, discountAmount = 3_000)

        assertRejected(couponCommand("CPN910", discountAmount = 3_000), ApiStatus.ORDER_COUPON_UNAVAILABLE)
        assertThat(couponUseOf("CPN910")).isEqualTo("N" to null)
    }

    @Test
    fun `이미 사용한 쿠폰으로는 주문을 받지 않는다`() {
        // 레거시 초기 데이터: CPN003 은 회원 1002 의 1만원 쿠폰이고 ORD20260301005 에 사용됐다
        assertRejected(couponCommand("CPN003", discountAmount = 10_000), ApiStatus.ORDER_COUPON_UNAVAILABLE)
        assertThat(couponUseOf("CPN003")).isEqualTo("Y" to "ORD20260301005")
    }

    @Test
    fun `없는 쿠폰으로는 주문을 받지 않는다`() {
        assertRejected(couponCommand("CPN999", discountAmount = 0), ApiStatus.ORDER_COUPON_UNAVAILABLE)
    }

    @Test
    fun `사용할 포인트가 음수이면 주문을 받지 않는다`() {
        assertRejected(orderCommand(G001, qty = 1).copy(pointAmount = -1), ApiStatus.ORDER_POINT_INVALID)
        assertThat(pointOf(MEMBER_1002)).isEqualTo(MEMBER_1002_POINT)
    }

    @Test
    fun `사용할 포인트가 결제 금액보다 크면 주문을 받지 않는다`() {
        // 회원 1002 의 보유 포인트(120,000)는 충분하다. 결제 금액(28,000)을 넘어서 거절된다
        val command = orderCommand(G001, qty = 1).copy(pointAmount = G001_PRICE + 1)

        assertRejected(command, ApiStatus.ORDER_POINT_INVALID)
        assertThat(pointOf(MEMBER_1002)).isEqualTo(MEMBER_1002_POINT)
    }

    /** 회원 1002 가 G001 한 개를 쿠폰으로 주문하는 요청 */
    private fun couponCommand(
        couponNo: String,
        discountAmount: Long,
    ): PlaceOrderCommand =
        orderCommand(G001, qty = 1).copy(couponNo = couponNo, expectedPayAmount = G001_PRICE - discountAmount)

    /** 주문이 [expected] 로 거절되고, 신규 DB 에 주문이 없고, 레거시 재고가 그대로인지 확인한다 */
    private fun assertRejected(
        command: PlaceOrderCommand,
        expected: ApiStatus,
    ) {
        assertThatThrownBy { orderService.placeOrder(command) }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertThat(it.apiStatus).isEqualTo(expected)
            }
        assertThat(orderRepository.count()).isZero()
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK)
    }
}
