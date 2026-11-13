package com.example.checkout.order.service

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 복구 작업자 설정 (`checkout.recovery.*`)
 *
 * @property stuckAfter PENDING·CANCELING 에 이 시간 넘게 머문 주문을 복구한다
 * @property unpaidAfter RESERVED 에서 이 시간 넘게 결제되지 않은 주문을 되돌린다
 */
@ConfigurationProperties("checkout.recovery")
data class OrderRecoveryProperties(
    val enabled: Boolean,
    val interval: Duration,
    val stuckAfter: Duration,
    val unpaidAfter: Duration,
)

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty("checkout.recovery.enabled", havingValue = "true")
class OrderRecoverySchedulingConfig

/**
 * 복구를 주기적으로 실행한다. 테스트에서는 꺼 두고 [OrderRecoveryService] 를 직접 호출한다
 */
@Component
@ConditionalOnProperty("checkout.recovery.enabled", havingValue = "true")
class OrderRecoveryScheduler(
    private val orderRecoveryService: OrderRecoveryService,
    private val properties: OrderRecoveryProperties,
) {
    @Scheduled(fixedDelayString = "\${checkout.recovery.interval}")
    fun run() {
        orderRecoveryService.recoverStuckOrders(properties.stuckAfter)
        orderRecoveryService.cancelUnpaidOrders(properties.unpaidAfter)
    }
}
