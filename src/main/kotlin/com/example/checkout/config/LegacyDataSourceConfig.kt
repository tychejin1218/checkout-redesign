package com.example.checkout.config

import com.zaxxer.hikari.HikariDataSource
import org.apache.ibatis.session.SqlSessionFactory
import org.mybatis.spring.SqlSessionFactoryBean
import org.mybatis.spring.SqlSessionTemplate
import org.mybatis.spring.annotation.MapperScan
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource
import org.apache.ibatis.session.Configuration as MyBatisConfiguration

/**
 * 레거시 DB (MyBatis)
 *
 * MyBatis 자동 구성은 @Primary DataSource(신규 DB)에 붙으려 하므로 SqlSessionFactory 를 레거시 DataSource 로 직접 정의한다.
 * 매퍼 인터페이스는 `legacy/mapper`, XML 은 `resources/mapper/legacy` 에 둔다.
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(
    basePackages = ["com.example.checkout.legacy.mapper"],
    sqlSessionTemplateRef = "legacySqlSessionTemplate",
)
class LegacyDataSourceConfig {
    @Bean
    fun legacyDataSource(properties: CheckoutDataSourceProperties): HikariDataSource =
        createSqliteDataSource(properties.legacy, "legacy-pool")

    @Bean
    fun legacySqlSessionFactory(
        @Qualifier("legacyDataSource") dataSource: DataSource,
    ): SqlSessionFactory {
        val factoryBean =
            SqlSessionFactoryBean().apply {
                setDataSource(dataSource)
                setMapperLocations(*PathMatchingResourcePatternResolver().getResources(MAPPER_LOCATIONS))
                // TB_GOODS.STOCK_QTY → stockQty
                setConfiguration(MyBatisConfiguration().apply { isMapUnderscoreToCamelCase = true })
            }
        return checkNotNull(factoryBean.getObject()) { "레거시 SqlSessionFactory 를 만들지 못했습니다." }
    }

    @Bean
    fun legacySqlSessionTemplate(
        @Qualifier("legacySqlSessionFactory") sqlSessionFactory: SqlSessionFactory,
    ): SqlSessionTemplate = SqlSessionTemplate(sqlSessionFactory)

    @Bean(TRANSACTION_MANAGER)
    fun legacyTransactionManager(
        @Qualifier("legacyDataSource") dataSource: DataSource,
    ): PlatformTransactionManager = DataSourceTransactionManager(dataSource)

    companion object {
        const val TRANSACTION_MANAGER = "legacyTransactionManager"
        private const val MAPPER_LOCATIONS = "classpath*:mapper/legacy/*.xml"
    }
}
