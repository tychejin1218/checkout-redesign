package com.example.checkout.config

import org.springframework.transaction.annotation.Transactional

/**
 * 레거시 DB 트랜잭션. 신규 DB 의 변경은 이 트랜잭션에 포함되지 않는다.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Transactional(transactionManager = LegacyDataSourceConfig.TRANSACTION_MANAGER)
annotation class LegacyTransactional
