package com.example.checkout.legacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class LegacyTimeTest {
    @Test
    fun `레거시에 쓰는 일시는 한국 시간으로 바꿔 YYYYMMDDHHMISS 로 만든다`() {
        // UTC 3월 1일 15:30 = 한국 시간 3월 2일 00:30
        val instant = Instant.parse("2026-03-01T15:30:00Z")

        assertThat(LegacyTime.format(instant)).isEqualTo("20260302003000")
    }
}
