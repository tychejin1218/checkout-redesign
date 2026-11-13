package com.example.checkout.order

import com.example.checkout.order.service.OrderNoGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class OrderNoGeneratorTest {
    // UTC 3월 1일 15:30 = 한국 시간 3월 2일 00:30
    private val generator = OrderNoGenerator(Clock.fixed(Instant.parse("2026-03-01T15:30:00Z"), ZoneOffset.UTC))

    @Test
    fun `주문번호는 N 과 한국 날짜와 무작위 13자로 된 20자다`() {
        val orderNo = generator.next()

        assertThat(orderNo).hasSize(20)
        assertThat(orderNo).startsWith("N260302")
        assertThat(orderNo.substring(7)).matches("[0-9A-Z]{13}")
    }

    @Test
    fun `레거시 주문번호와 접두어가 달라 겹치지 않는다`() {
        assertThat(generator.next()).doesNotStartWith("ORD")
    }

    @Test
    fun `부를 때마다 다른 번호가 나온다`() {
        val orderNos = List(1_000) { generator.next() }

        assertThat(orderNos.distinct()).hasSize(1_000)
    }
}
