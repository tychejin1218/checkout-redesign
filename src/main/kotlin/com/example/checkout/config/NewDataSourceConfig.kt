package com.example.checkout.config

import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.EntityManagerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.PlatformTransactionManager

/**
 * 신규 DB (JPA)
 *
 * DataSource 를 @Primary 로 두어 Spring Boot 의 JPA 자동 구성(EntityManagerFactory, Repository)이 신규 DB 에 붙게 한다.
 * 트랜잭션 매니저는 레거시 쪽과 구분되도록 직접 정의한다. 트랜잭션 매니저 빈이 하나라도 있으면 자동 구성은 만들지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class NewDataSourceConfig {
    @Bean
    @Primary
    fun newDataSource(properties: CheckoutDataSourceProperties): HikariDataSource =
        createSqliteDataSource(properties.new, "new-pool")

    // Spring Data JPA Repository 는 이름이 "transactionManager" 인 빈을 찾으므로 별칭으로 함께 등록한다
    @Bean(name = [TRANSACTION_MANAGER, "transactionManager"])
    @Primary
    fun newTransactionManager(entityManagerFactory: EntityManagerFactory): PlatformTransactionManager =
        JpaTransactionManager(entityManagerFactory)

    companion object {
        const val TRANSACTION_MANAGER = "newTransactionManager"
    }
}
