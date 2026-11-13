package com.example.checkout.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * 신규 DB 와 레거시 DB 를 초기화 스크립트 직후 상태로 되돌린다. 테스트가 매번 같은 상태에서 시작하도록 쓴다.
 */
@Component
class DatabaseResetter(
    @Qualifier("newDataSource") newDataSource: DataSource,
    @Qualifier("legacyDataSource") legacyDataSource: DataSource,
    properties: CheckoutDataSourceProperties,
) {
    private val initializers =
        listOf(
            SqliteDatabaseInitializer(newDataSource, properties.new.initScripts),
            SqliteDatabaseInitializer(legacyDataSource, properties.legacy.initScripts),
        )

    fun resetAll() {
        initializers.forEach { it.reset() }
    }
}
