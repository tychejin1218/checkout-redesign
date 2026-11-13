package com.example.checkout

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import java.time.ZoneOffset
import java.util.TimeZone

@SpringBootApplication
@ConfigurationPropertiesScan
class CheckoutApplication

fun main(args: Array<String>) {
    // 서버 시간대와 관계없이 UTC 로 동작시킨다
    // (테스트 JVM 은 build.gradle.kts 의 user.timezone 으로 같게 맞춘다)
    TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC))
    runApplication<CheckoutApplication>(*args)
}
