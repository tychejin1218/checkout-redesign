package com.example.checkout.config

import com.example.checkout.legacy.mapper.LegacyConnectionMapper
import com.example.checkout.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DatabaseResetTest(
    @Autowired private val databaseResetter: DatabaseResetter,
    @Autowired private val legacyConnectionMapper: LegacyConnectionMapper,
    @Autowired @Qualifier("newDataSource") newDataSource: DataSource,
) : IntegrationTest() {
    private val newJdbcTemplate = JdbcTemplate(newDataSource)

    @Test
    fun `초기화하면 두 DB 가 스크립트 직후 상태로 돌아온다`() {
        changeBothDatabases()

        databaseResetter.resetAll()

        assertThat(legacyConnectionMapper.findStockQty("G002")).isEqualTo(3)
        assertThat(legacyConnectionMapper.countOrders()).isEqualTo(6)
        assertThat(newMetaValue()).isEqualTo("new")
    }

    @Test
    @Order(1)
    fun `앞 테스트가 두 DB 를 바꾸고 커밋해도`() {
        changeBothDatabases()

        assertThat(legacyConnectionMapper.findStockQty("G002")).isZero()
        assertThat(newMetaValue()).isEqualTo("changed")
    }

    @Test
    @Order(2)
    fun `다음 테스트는 초기 상태에서 시작한다`() {
        assertThat(legacyConnectionMapper.findStockQty("G002")).isEqualTo(3)
        assertThat(newMetaValue()).isEqualTo("new")
    }

    private fun changeBothDatabases() {
        legacyConnectionMapper.updateStockQty("G002", 0)
        newJdbcTemplate.update("UPDATE app_meta SET meta_value = 'changed' WHERE meta_key = 'db_name'")
    }

    private fun newMetaValue(): String? =
        newJdbcTemplate.queryForObject("SELECT meta_value FROM app_meta WHERE meta_key = 'db_name'", String::class.java)
}
