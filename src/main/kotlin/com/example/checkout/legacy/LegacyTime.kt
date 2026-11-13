package com.example.checkout.legacy

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 레거시 DB 의 시간 표현
 *
 * 신규 시스템과 신규 DB 는 UTC 로 동작하지만, 레거시 DB 의 일시(`'YYYYMMDDHHMISS'`)는 시간대 표시 없이
 * 한국 시간으로 기록돼 있다고 가정한다(DESIGN.md 9장). 레거시에 쓰는 값은 여기서 한국 시간으로 바꾼다.
 */
object LegacyTime {
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZONE)

    /** 레거시 일시 컬럼에 넣을 값 */
    fun format(instant: Instant): String = DATE_TIME.format(instant)
}
