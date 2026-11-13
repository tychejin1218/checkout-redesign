package com.example.checkout.config

import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * SQLite 파일을 여는 커넥션 풀을 만들고, DB 를 초기화 스크립트 직후 상태로 만든다.
 *
 * DataSource 빈을 만드는 시점에 초기화해야 JPA·MyBatis 가 뜨기 전에 스키마가 준비된다.
 */
internal fun createSqliteDataSource(
    properties: SqliteProperties,
    poolName: String,
): HikariDataSource {
    // sqlite-jdbc 는 DB 파일은 만들지만 상위 폴더는 만들지 않는다
    Path
        .of(properties.path)
        .toAbsolutePath()
        .parent
        ?.let { Files.createDirectories(it) }

    val dataSource =
        HikariDataSource().also {
            it.jdbcUrl = "jdbc:sqlite:${properties.path}"
            it.driverClassName = "org.sqlite.JDBC"
            it.poolName = poolName
            it.maximumPoolSize = properties.maximumPoolSize
            it.addDataSourceProperty("busy_timeout", properties.busyTimeoutMs.toString())
            it.addDataSourceProperty("foreign_keys", properties.foreignKeys.toString())
            // 트랜잭션을 시작할 때 쓰기 잠금을 잡는다(BEGIN IMMEDIATE). 기본값(DEFERRED)은 읽은 뒤 쓰려 할 때
            // 다른 트랜잭션과 서로를 기다리게 되면 busy_timeout 과 무관하게 즉시 SQLITE_BUSY 로 실패한다
            it.addDataSourceProperty("transaction_mode", "IMMEDIATE")
        }
    SqliteDatabaseInitializer(dataSource, properties.initScripts).reset()
    return dataSource
}
