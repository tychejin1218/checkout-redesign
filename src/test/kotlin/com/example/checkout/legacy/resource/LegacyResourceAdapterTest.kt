package com.example.checkout.legacy.resource

import com.example.checkout.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 레거시 자원 포트의 멱등성: 같은 주문번호로 몇 번을 호출해도 레거시 자원은 한 번만 바뀐다
 */
class LegacyResourceAdapterTest(
    @Autowired private val legacyResourcePort: LegacyResourcePort,
) : IntegrationTest() {
    private val resources =
        OrderResources(
            orderNo = "NEW-TEST-1",
            memberNo = 1002,
            lines = listOf(OrderResources.Line("G001", qty = 2, unitPrice = 28_000)),
            couponNo = null,
            pointAmount = 5_000,
        )

    @Test
    fun `같은 주문의 확보를 반복해도 재고와 포인트는 한 번만 줄어든다`() {
        val first = legacyResourcePort.reserve(resources)
        val second = legacyResourcePort.reserve(resources)

        assertThat(first).isEqualTo(ReserveResult.Reserved)
        assertThat(second).isEqualTo(ReserveResult.AlreadyDecided(ApplyStat.APPLIED))
        assertThat(stockOf("G001")).isEqualTo(48)
        assertThat(pointOf(1002)).isEqualTo(115_000)
    }

    @Test
    fun `같은 주문의 반환을 반복해도 재고와 포인트는 한 번만 돌아온다`() {
        legacyResourcePort.reserve(resources)

        val first = legacyResourcePort.release(resources)
        val second = legacyResourcePort.release(resources)

        assertThat(first).isEqualTo(ReleaseResult.RELEASED)
        assertThat(second).isEqualTo(ReleaseResult.ALREADY_RELEASED)
        assertThat(stockOf("G001")).isEqualTo(50)
        assertThat(pointOf(1002)).isEqualTo(120_000)
    }

    @Test
    fun `차감된 적이 없는 주문을 반환하면 아무것도 바뀌지 않는다`() {
        val result = legacyResourcePort.release(resources)

        assertThat(result).isEqualTo(ReleaseResult.NOT_APPLIED)
        assertThat(stockOf("G001")).isEqualTo(50)
        assertThat(pointOf(1002)).isEqualTo(120_000)
    }

    @Test
    fun `반환한 주문을 다시 확보할 수 없다`() {
        legacyResourcePort.reserve(resources)
        legacyResourcePort.release(resources)

        val result = legacyResourcePort.reserve(resources)

        assertThat(result).isEqualTo(ReserveResult.AlreadyDecided(ApplyStat.REVERTED))
        assertThat(stockOf("G001")).isEqualTo(50)
    }

    @Test
    fun `여러 상품 중 하나라도 재고가 부족하면 다른 상품도 차감하지 않는다`() {
        val twoGoods =
            resources.copy(
                lines =
                    listOf(
                        OrderResources.Line("G001", qty = 2, unitPrice = 28_000),
                        // G002 재고는 3
                        OrderResources.Line("G002", qty = 4, unitPrice = 36_000),
                    ),
            )

        val result = legacyResourcePort.reserve(twoGoods)

        assertThat(result).isEqualTo(ReserveResult.Rejected(ReserveFailReason.OUT_OF_STOCK))
        assertThat(stockOf("G001")).isEqualTo(50)
        assertThat(stockOf("G002")).isEqualTo(3)
        assertThat(pointOf(1002)).isEqualTo(120_000)
        assertThat(markerOf(twoGoods.orderNo)).isNull()
    }

    @Test
    fun `이미 차감이 반영된 주문은 거부 표식으로 덮어쓰지 못한다`() {
        legacyResourcePort.reserve(resources)

        val stat = legacyResourcePort.rejectIfUndecided(resources.orderNo)

        assertThat(stat).isEqualTo(ApplyStat.APPLIED)
        assertThat(markerOf(resources.orderNo)).isEqualTo("A")
    }
}
