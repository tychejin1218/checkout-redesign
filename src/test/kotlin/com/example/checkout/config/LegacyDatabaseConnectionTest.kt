package com.example.checkout.config

import com.example.checkout.legacy.mapper.LegacyConnectionMapper
import com.example.checkout.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class LegacyDatabaseConnectionTest(
    @Autowired private val legacyConnectionMapper: LegacyConnectionMapper,
) : IntegrationTest() {
    @Test
    fun `MyBatis 로 레거시 DB 의 초기 데이터를 읽는다`() {
        assertThat(legacyConnectionMapper.countGoods()).isEqualTo(4)
        assertThat(legacyConnectionMapper.countOrders()).isEqualTo(6)
        assertThat(legacyConnectionMapper.findStockQty("G002")).isEqualTo(3)
    }

    @Test
    fun `없는 상품의 재고는 null 이다`() {
        assertThat(legacyConnectionMapper.findStockQty("NONE")).isNull()
    }

    @Test
    fun `레거시 DB 에는 신규 DB 의 테이블이 없다`() {
        assertThat(legacyConnectionMapper.countTablesByName("TB_GOODS")).isEqualTo(1)
        assertThat(legacyConnectionMapper.countTablesByName("app_meta")).isZero()
    }
}
