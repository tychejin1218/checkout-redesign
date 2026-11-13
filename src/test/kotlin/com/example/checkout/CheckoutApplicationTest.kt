package com.example.checkout

import com.example.checkout.support.IntegrationTest
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

class CheckoutApplicationTest(
    @Autowired private val primaryDataSource: DataSource,
    @Autowired @Qualifier("newDataSource") private val newDataSource: HikariDataSource,
    @Autowired @Qualifier("legacyDataSource") private val legacyDataSource: HikariDataSource,
    @Autowired private val primaryTransactionManager: PlatformTransactionManager,
    @Autowired @Qualifier("newTransactionManager") private val newTransactionManager: PlatformTransactionManager,
    @Autowired @Qualifier("legacyTransactionManager") private val legacyTransactionManager: PlatformTransactionManager,
) : IntegrationTest() {
    @Test
    fun `신규 DB 와 레거시 DB 는 서로 다른 SQLite 파일을 쓴다`() {
        assertThat(newDataSource.jdbcUrl).isEqualTo("jdbc:sqlite:build/test-db/new.db")
        assertThat(legacyDataSource.jdbcUrl).isEqualTo("jdbc:sqlite:build/test-db/legacy.db")
        assertThat(newDataSource).isNotSameAs(legacyDataSource)
    }

    @Test
    fun `기본 DataSource 는 신규 DB 다`() {
        assertThat(primaryDataSource).isSameAs(newDataSource)
    }

    @Test
    fun `트랜잭션 매니저는 DB 마다 따로 있고 기본은 신규 DB 다`() {
        assertThat(newTransactionManager).isInstanceOf(JpaTransactionManager::class.java)
        assertThat(legacyTransactionManager).isInstanceOf(DataSourceTransactionManager::class.java)
        assertThat((legacyTransactionManager as DataSourceTransactionManager).dataSource).isSameAs(legacyDataSource)
        assertThat(primaryTransactionManager).isSameAs(newTransactionManager)
    }
}
