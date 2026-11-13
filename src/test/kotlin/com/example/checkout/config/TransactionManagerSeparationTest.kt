package com.example.checkout.config

import com.example.checkout.legacy.mapper.LegacyConnectionMapper
import com.example.checkout.support.IntegrationTest
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * 두 DB 의 트랜잭션이 서로 독립임을 확인한다.
 *
 * 한 서비스 메서드에서 두 DB 를 모두 바꾸고 예외가 나면, 그 메서드의 트랜잭션에 속한 DB 만 롤백되고
 * 다른 DB 의 변경은 남는다. DB 간 쓰기를 따로 설계해야 하는 이유다(DESIGN.md 5장).
 */
class TransactionManagerSeparationTest(
    @Autowired private val probe: Probe,
    @Autowired private val legacyConnectionMapper: LegacyConnectionMapper,
    @Autowired private val entityManager: EntityManager,
) : IntegrationTest() {
    @Test
    fun `레거시 트랜잭션에서 예외가 나면 레거시 변경만 롤백되고 신규 변경은 남는다`() {
        assertThatThrownBy { probe.changeBothInLegacyTransactionThenFail() }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(legacyConnectionMapper.findStockQty("G002")).isEqualTo(3)
        assertThat(newMetaValue()).isEqualTo("changed")
    }

    @Test
    fun `신규 트랜잭션에서 예외가 나면 신규 변경만 롤백되고 레거시 변경은 남는다`() {
        assertThatThrownBy { probe.changeBothInNewTransactionThenFail() }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(newMetaValue()).isEqualTo("new")
        assertThat(legacyConnectionMapper.findStockQty("G002")).isZero()
    }

    @Test
    fun `예외가 없으면 각 트랜잭션의 변경이 커밋된다`() {
        probe.changeLegacy()
        probe.changeNew()

        assertThat(legacyConnectionMapper.findStockQty("G002")).isZero()
        assertThat(newMetaValue()).isEqualTo("changed")
    }

    private fun newMetaValue(): Any? =
        entityManager
            .createNativeQuery("SELECT meta_value FROM app_meta WHERE meta_key = 'db_name'")
            .singleResult

    @TestConfiguration(proxyBeanMethods = false)
    class ProbeConfig {
        @Bean
        fun probe(
            legacyConnectionMapper: LegacyConnectionMapper,
            entityManager: EntityManager,
            @Qualifier("newDataSource") newDataSource: DataSource,
        ) = Probe(legacyConnectionMapper, entityManager, JdbcTemplate(newDataSource))
    }

    /** 트랜잭션 프록시가 감쌀 수 있도록 open 으로 둔다 */
    open class Probe(
        private val legacyConnectionMapper: LegacyConnectionMapper,
        private val entityManager: EntityManager,
        private val newJdbcTemplate: JdbcTemplate,
    ) {
        @LegacyTransactional
        open fun changeLegacy() {
            legacyConnectionMapper.updateStockQty("G002", 0)
        }

        @NewTransactional
        open fun changeNew() {
            entityManager.createNativeQuery(CHANGE_NEW_SQL).executeUpdate()
        }

        @LegacyTransactional
        open fun changeBothInLegacyTransactionThenFail() {
            legacyConnectionMapper.updateStockQty("G002", 0)
            // 신규 DB 는 이 트랜잭션에 속하지 않으므로 문장 단위로 바로 커밋된다
            newJdbcTemplate.update(CHANGE_NEW_SQL)
            error("레거시 트랜잭션 안에서 실패")
        }

        @NewTransactional
        open fun changeBothInNewTransactionThenFail() {
            entityManager.createNativeQuery(CHANGE_NEW_SQL).executeUpdate()
            // 레거시 DB 는 이 트랜잭션에 속하지 않으므로 문장 단위로 바로 커밋된다
            legacyConnectionMapper.updateStockQty("G002", 0)
            error("신규 트랜잭션 안에서 실패")
        }

        private companion object {
            const val CHANGE_NEW_SQL = "UPDATE app_meta SET meta_value = 'changed' WHERE meta_key = 'db_name'"
        }
    }
}
