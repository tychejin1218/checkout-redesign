package com.example.checkout.order.service

import com.example.checkout.legacy.LegacyTime
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Clock
import java.time.format.DateTimeFormatter

/**
 * 신규 주문번호 (20자): `N` + 한국 날짜 `yyMMdd` + 무작위 13자(0-9, A-Z)
 *
 * - 레거시(`ORD` + 날짜 + 3자리 일련번호)와 접두어가 달라 겹치지 않는다.
 * - 일련번호를 채번하지 않으므로 동시 요청이 같은 번호를 받으려고 경합하지 않는다.
 * - 이 번호는 레거시 DB 에도 기록되므로(TB_NEW_ORD_APPLY, TB_COUPON.USE_ORD_NO) 날짜는 레거시와 같은 한국 시간 기준이다.
 * - 무작위 부분이 겹칠 확률은 매우 낮지만 0 은 아니다. 겹치면 `orders` 의 PK 가 거절하고 새 번호로 다시 시도한다
 *   ([OrderService], DESIGN.md 4.5).
 */
@Component
class OrderNoGenerator(
    private val clock: Clock,
) {
    private val random = SecureRandom()

    fun next(): String {
        val randomPart = String(CharArray(RANDOM_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })
        return PREFIX + DATE.format(clock.instant()) + randomPart
    }

    companion object {
        private const val PREFIX = "N"
        private const val RANDOM_LENGTH = 13
        private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private val DATE = DateTimeFormatter.ofPattern("yyMMdd").withZone(LegacyTime.ZONE)
    }
}
