package com.example.checkout.config

import com.example.checkout.support.IntegrationTest
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class NewDatabaseConnectionTest(
    @Autowired private val entityManager: EntityManager,
) : IntegrationTest() {
    @Test
    fun `JPA 로 신규 DB 의 초기 데이터를 읽는다`() {
        val value =
            entityManager
                .createNativeQuery("SELECT meta_value FROM app_meta WHERE meta_key = 'db_name'")
                .singleResult

        assertThat(value).isEqualTo("new")
    }

    @Test
    fun `신규 DB 에는 레거시 DB 의 테이블이 없다`() {
        assertThat(countTables("app_meta")).isEqualTo(1)
        assertThat(countTables("TB_GOODS")).isZero()
    }

    private fun countTables(tableName: String): Int =
        (
            entityManager
                .createNativeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = :name")
                .setParameter("name", tableName)
                .singleResult as Number
        ).toInt()
}
