package com.example.checkout.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.io.Resource

/**
 * 신규 DB 와 레거시 DB 설정 (`checkout.datasource.*`)
 */
@ConfigurationProperties("checkout.datasource")
data class CheckoutDataSourceProperties(
    val new: SqliteProperties,
    val legacy: SqliteProperties,
)

/**
 * SQLite 파일 하나의 설정
 *
 * @property path DB 파일 경로. 없으면 상위 폴더와 함께 만든다
 * @property initScripts 시작할 때 순서대로 실행할 스키마·초기 데이터 스크립트
 * @property foreignKeys 외래 키를 검사할지. SQLite 는 커넥션마다 켜야 하고 기본값은 꺼짐이다
 * @property busyTimeoutMs 다른 커넥션이 쓰는 중일 때 실패하지 않고 기다리는 시간
 */
data class SqliteProperties(
    val path: String,
    val initScripts: List<Resource>,
    val maximumPoolSize: Int = 5,
    val busyTimeoutMs: Int = 5000,
    val foreignKeys: Boolean = false,
)
