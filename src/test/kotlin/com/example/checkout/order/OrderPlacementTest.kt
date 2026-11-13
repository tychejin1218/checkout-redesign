package com.example.checkout.order

import com.example.checkout.common.exception.ApiException
import com.example.checkout.common.type.ApiStatus
import com.example.checkout.domain.entity.OrderStatus
import com.example.checkout.domain.repository.OrderItemRepository
import com.example.checkout.domain.repository.OrderRepository
import com.example.checkout.order.dto.OrderLine
import com.example.checkout.order.dto.PlaceOrderCommand
import com.example.checkout.order.service.OrderService
import com.example.checkout.order.service.OrderStep
import com.example.checkout.support.IntegrationTest
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException

/**
 * 주문 생성: 신규 DB 의 주문과 레거시 DB 의 재고·쿠폰·포인트가 같은 결론에 이르는지 확인한다
 */
class OrderPlacementTest(
    @Autowired private val orderService: OrderService,
    @Autowired private val orderRepository: OrderRepository,
    @Autowired private val orderItemRepository: OrderItemRepository,
) : IntegrationTest() {
    @Test
    fun `주문하면 신규 DB 에 RESERVED 주문이 남고 레거시 재고가 줄고 반영 기록 A 가 남는다`() {
        val result = orderService.placeOrder(orderCommand(G001, qty = 2))

        assertThat(result.status).isEqualTo(OrderStatus.RESERVED)
        assertThat(result.payAmount).isEqualTo(56_000)
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK - 2)
        assertThat(markerOf(result.orderNo)).isEqualTo("A")
    }

    @Test
    fun `품목에는 주문 시점의 단가와 상품명이 저장된다`() {
        val result = orderService.placeOrder(orderCommand(G001, qty = 2))

        val item = orderItemRepository.findByIdOrderNoOrderByIdSeq(result.orderNo).single()
        assertThat(item.goodsCd).isEqualTo(G001)
        assertThat(item.goodsName).isEqualTo("자바의 정석")
        assertThat(item.qty).isEqualTo(2)
        assertThat(item.unitPrice).isEqualTo(G001_PRICE)
    }

    @Test
    fun `쿠폰과 포인트를 쓰면 재고와 함께 레거시에서 차감된다`() {
        issueCoupon("CPN900", MEMBER_1002, discountAmount = 10_000)
        val command =
            PlaceOrderCommand(
                memberNo = MEMBER_1002,
                lines = listOf(OrderLine(G001, 2)),
                expectedPayAmount = 46_000,
                idempotencyKey = "key-coupon-point",
                couponNo = "CPN900",
                pointAmount = 6_000,
            )

        val result = orderService.placeOrder(command)

        assertThat(result.status).isEqualTo(OrderStatus.RESERVED)
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK - 2)
        assertThat(couponUseOf("CPN900")).isEqualTo("Y" to result.orderNo)
        assertThat(pointOf(MEMBER_1002)).isEqualTo(MEMBER_1002_POINT - 6_000)
    }

    @Test
    fun `재고보다 많이 주문하면 FAILED 가 되고 레거시에는 아무것도 남지 않는다`() {
        val result = orderService.placeOrder(orderCommand(G002, qty = G002_STOCK + 1, unitPrice = G002_PRICE))

        assertThat(result.status).isEqualTo(OrderStatus.FAILED)
        assertThat(result.failReason).isEqualTo("OUT_OF_STOCK")
        assertThat(stockOf(G002)).isEqualTo(G002_STOCK)
        assertThat(markerOf(result.orderNo)).isNull()
    }

    @Test
    fun `포인트가 부족하면 먼저 차감한 재고와 쿠폰까지 함께 롤백된다`() {
        issueCoupon("CPN901", MEMBER_1001, discountAmount = 3_000)
        val command =
            PlaceOrderCommand(
                memberNo = MEMBER_1001,
                lines = listOf(OrderLine(G001, 1)),
                expectedPayAmount = 25_000,
                idempotencyKey = "key-point-short",
                couponNo = "CPN901",
                // 회원 1001 의 보유 포인트는 5,000
                pointAmount = 6_000,
            )

        val result = orderService.placeOrder(command)

        assertThat(result.status).isEqualTo(OrderStatus.FAILED)
        assertThat(result.failReason).isEqualTo("POINT_INSUFFICIENT")
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK)
        assertThat(couponUseOf("CPN901")).isEqualTo("N" to null)
        assertThat(pointOf(MEMBER_1001)).isEqualTo(5_000)
        assertThat(markerOf(result.orderNo)).isNull()
    }

    @Test
    fun `사용자가 확인한 금액과 서버가 계산한 금액이 다르면 주문을 받지 않는다`() {
        val command = orderCommand(G001, qty = 1).copy(expectedPayAmount = G001_PRICE - 1_000)

        assertThatThrownBy { orderService.placeOrder(command) }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertThat(it.apiStatus).isEqualTo(ApiStatus.ORDER_AMOUNT_CHANGED)
            }
        assertThat(orderRepository.count()).isZero()
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK)
    }

    @Test
    fun `금액 계산과 재고 차감 사이에 가격이 바뀌면 차감하지 않고 FAILED 가 된다`() {
        every { orderStepObserver.passed(OrderStep.PENDING_RECORDED, any()) } answers {
            legacyJdbc.update("UPDATE TB_GOODS SET SALE_PRC = 30000 WHERE GOODS_CD = ?", G001)
        }

        val result = orderService.placeOrder(orderCommand(G001, qty = 1))

        assertThat(result.status).isEqualTo(OrderStatus.FAILED)
        assertThat(result.failReason).isEqualTo("PRICE_CHANGED")
        assertThat(result.payAmount).isEqualTo(G001_PRICE)
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK)
    }

    @Test
    fun `레거시에 없는 회원의 주문은 받지 않는다`() {
        val command = orderCommand(G001, qty = 1, memberNo = UNKNOWN_MEMBER)

        assertThatThrownBy { orderService.placeOrder(command) }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertThat(it.apiStatus).isEqualTo(ApiStatus.ORDER_MEMBER_UNAVAILABLE)
            }
        assertThat(orderRepository.count()).isZero()
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK)
    }

    @Test
    fun `사용 여부가 N 인 회원의 주문은 받지 않는다`() {
        legacyJdbc.update("UPDATE TB_MEMBER SET USE_YN = 'N' WHERE MBR_NO = ?", MEMBER_1002)

        assertThatThrownBy { orderService.placeOrder(orderCommand(G001, qty = 1)) }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertThat(it.apiStatus).isEqualTo(ApiStatus.ORDER_MEMBER_UNAVAILABLE)
            }
        assertThat(orderRepository.count()).isZero()
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK)
    }

    @Test
    fun `판매 중이 아닌 상품은 주문을 받지 않는다`() {
        val command = orderCommand("G003", qty = 1, unitPrice = 45_000)

        assertThatThrownBy { orderService.placeOrder(command) }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertThat(it.apiStatus).isEqualTo(ApiStatus.ORDER_GOODS_UNAVAILABLE)
            }
        assertThat(orderRepository.count()).isZero()
    }

    @Test
    fun `같은 멱등 키로 다시 요청하면 같은 주문을 돌려주고 재고는 한 번만 줄어든다`() {
        val command = orderCommand(G001, qty = 2, idempotencyKey = "key-retry")

        val first = orderService.placeOrder(command)
        val second = orderService.placeOrder(command)

        assertThat(second.orderNo).isEqualTo(first.orderNo)
        assertThat(orderRepository.count()).isEqualTo(1)
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK - 2)
    }

    @Test
    fun `같은 멱등 키로 품목 순서만 바꿔 다시 요청해도 같은 주문을 돌려준다`() {
        val command =
            PlaceOrderCommand(
                memberNo = MEMBER_1002,
                lines = listOf(OrderLine(G001, 2), OrderLine(G002, 1)),
                expectedPayAmount = G001_PRICE * 2 + G002_PRICE,
                idempotencyKey = "key-reordered",
            )

        val first = orderService.placeOrder(command)
        val second = orderService.placeOrder(command.copy(lines = command.lines.reversed()))

        assertThat(second.orderNo).isEqualTo(first.orderNo)
        assertThat(orderRepository.count()).isEqualTo(1)
    }

    @Test
    fun `같은 멱등 키로 수량이 다른 요청을 보내면 거절하고 기존 주문과 재고는 그대로다`() {
        val first = orderService.placeOrder(orderCommand(G001, qty = 2, idempotencyKey = "key-reused"))

        assertKeyReused(orderCommand(G001, qty = 3, idempotencyKey = "key-reused"))

        assertThat(orderRepository.count()).isEqualTo(1)
        assertThat(orderRepository.findByOrderNo(first.orderNo)?.payAmount).isEqualTo(G001_PRICE * 2)
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK - 2)
    }

    @Test
    fun `같은 멱등 키로 상품이 다른 요청을 보내면 거절한다`() {
        orderService.placeOrder(orderCommand(G001, qty = 1, idempotencyKey = "key-reused"))

        assertKeyReused(orderCommand(G002, qty = 1, unitPrice = G002_PRICE, idempotencyKey = "key-reused"))

        assertThat(stockOf(G002)).isEqualTo(G002_STOCK)
    }

    @Test
    fun `같은 멱등 키로 쿠폰이 다른 요청을 보내면 거절하고 쿠폰은 쓰이지 않는다`() {
        issueCoupon("CPN920", MEMBER_1002, discountAmount = 3_000)
        orderService.placeOrder(orderCommand(G001, qty = 1, idempotencyKey = "key-reused"))

        val withCoupon = orderCommand(G001, qty = 1, idempotencyKey = "key-reused")
        assertKeyReused(withCoupon.copy(couponNo = "CPN920", expectedPayAmount = G001_PRICE - 3_000))

        assertThat(couponUseOf("CPN920")).isEqualTo("N" to null)
    }

    @Test
    fun `같은 멱등 키로 포인트가 다른 요청을 보내면 거절하고 포인트는 줄지 않는다`() {
        orderService.placeOrder(orderCommand(G001, qty = 1, idempotencyKey = "key-reused"))

        assertKeyReused(orderCommand(G001, qty = 1, idempotencyKey = "key-reused").copy(pointAmount = 1_000))

        assertThat(pointOf(MEMBER_1002)).isEqualTo(MEMBER_1002_POINT)
    }

    @Test
    fun `수량과 단가를 곱한 금액이 Int 범위를 넘어도 금액이 정확히 계산된다`() {
        // 28,000 × 100,000 = 2,800,000,000 > Int.MAX_VALUE(2,147,483,647)
        val command = orderCommand(G001, qty = 100_000)

        val result = orderService.placeOrder(command)

        assertThat(command.expectedPayAmount).isEqualTo(2_800_000_000L)
        assertThat(result.payAmount).isEqualTo(2_800_000_000L)
        // 금액 검증은 통과하고, 재고가 모자라 레거시 차감에서 거절된다
        assertThat(result.failReason).isEqualTo("OUT_OF_STOCK")
        assertThat(orderRepository.findByOrderNo(result.orderNo)?.itemAmount).isEqualTo(2_800_000_000L)
    }

    @Test
    fun `다른 회원이 같은 멱등 키를 써도 각자의 주문이 만들어진다`() {
        val mine = orderService.placeOrder(orderCommand(G001, memberNo = MEMBER_1002, idempotencyKey = "same-key"))
        val others = orderService.placeOrder(orderCommand(G001, memberNo = MEMBER_1001, idempotencyKey = "same-key"))

        assertThat(others.orderNo).isNotEqualTo(mine.orderNo)
        assertThat(orderRepository.findByOrderNo(others.orderNo)?.memberNo).isEqualTo(MEMBER_1001)
        assertThat(orderRepository.count()).isEqualTo(2)
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK - 2)
    }

    @Test
    fun `주문번호가 다른 주문과 겹치면 그 주문을 덮어쓰지 않고 새 번호로 기록한다`() {
        val existing = orderService.placeOrder(orderCommand(G001, qty = 2, memberNo = MEMBER_1002))
        // 다음 주문이 기존 주문과 같은 번호를 두 번 받고, 세 번째에 새 번호를 받는다
        every { orderNoGenerator.next() } returnsMany listOf(existing.orderNo, existing.orderNo, "N260301NEWNUMBER0001")

        val result = orderService.placeOrder(orderCommand(G001, qty = 1, memberNo = MEMBER_1001))

        assertThat(result.orderNo).isEqualTo("N260301NEWNUMBER0001")
        assertThat(result.status).isEqualTo(OrderStatus.RESERVED)
        val untouched = checkNotNull(orderRepository.findByOrderNo(existing.orderNo))
        assertThat(untouched.memberNo).isEqualTo(MEMBER_1002)
        assertThat(untouched.payAmount).isEqualTo(G001_PRICE * 2)
        assertThat(untouched.status).isEqualTo(OrderStatus.RESERVED)
        assertThat(orderItemRepository.findByIdOrderNoOrderByIdSeq(existing.orderNo).single().qty).isEqualTo(2)
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK - 3)
    }

    @Test
    fun `주문번호가 계속 겹치면 주문을 받지 않고 기존 주문과 재고는 그대로다`() {
        val existing = orderService.placeOrder(orderCommand(G001, qty = 2, memberNo = MEMBER_1002))
        every { orderNoGenerator.next() } returns existing.orderNo

        assertThatThrownBy { orderService.placeOrder(orderCommand(G001, qty = 1, memberNo = MEMBER_1001)) }
            .isInstanceOf(DataAccessException::class.java)
        assertThat(orderRepository.count()).isEqualTo(1)
        assertThat(orderRepository.findByOrderNo(existing.orderNo)?.memberNo).isEqualTo(MEMBER_1002)
        assertThat(stockOf(G001)).isEqualTo(G001_STOCK - 2)
    }

    private fun assertKeyReused(command: PlaceOrderCommand) {
        assertThatThrownBy { orderService.placeOrder(command) }
            .isInstanceOfSatisfying(ApiException::class.java) {
                assertThat(it.apiStatus).isEqualTo(ApiStatus.ORDER_IDEMPOTENCY_KEY_REUSED)
            }
    }
}
