package com.example.checkout.legacy.resource

import com.example.checkout.legacy.model.LegacyCoupon
import com.example.checkout.legacy.model.LegacyGoods

/**
 * 레거시 DB 에 있는 자원(재고·가격·쿠폰·포인트)을 신규 시스템이 쓰는 유일한 통로
 *
 * 변경은 "주문 한 건 단위로 확보한다 / 반환한다" 로만 노출한다. 모든 변경은 주문번호 기준으로 멱등하다.
 * 같은 주문번호로 몇 번을 호출해도 레거시의 재고·쿠폰·포인트는 한 번만 바뀐다.
 * 이 자원들이 신규 DB 로 옮겨지면 구현만 바꾼다.
 */
interface LegacyResourcePort {
    fun findGoods(goodsCd: String): LegacyGoods?

    fun findCoupon(couponNo: String): LegacyCoupon?

    /** 재고·쿠폰·포인트를 한 트랜잭션으로 차감한다. 하나라도 조건에 맞지 않으면 아무것도 바꾸지 않는다 */
    fun reserve(resources: OrderResources): ReserveResult

    /** [reserve] 로 차감한 것을 되돌린다. 차감된 적이 없거나 이미 되돌렸으면 아무것도 바꾸지 않는다 */
    fun release(resources: OrderResources): ReleaseResult

    /**
     * 이 주문의 차감이 아직 반영되지 않았다면 앞으로도 반영되지 않게 막고, 확정된 반영 기록을 돌려준다.
     *
     * 복구 작업자가 "반영 안 됨" 으로 결론 내리기 전에 부른다. 뒤늦게 도착한 [reserve] 는 아무것도 하지 못한다.
     */
    fun rejectIfUndecided(orderNo: String): ApplyStat
}

/**
 * 주문 한 건이 레거시에서 확보하는 자원. 신규 DB 의 주문 스냅샷에서 만든다
 */
data class OrderResources(
    val orderNo: String,
    val memberNo: Long,
    val lines: List<Line>,
    val couponNo: String?,
    val pointAmount: Int,
) {
    /** @property unitPrice 주문 시점 단가. 레거시의 현재 가격과 다르면 차감하지 않는다 */
    data class Line(
        val goodsCd: String,
        val qty: Int,
        val unitPrice: Int,
    )
}

/**
 * 레거시 TB_NEW_ORD_APPLY.APPLY_STAT. 주문 한 건의 차감이 레거시에 반영됐는지에 대한 유일한 답이다
 */
enum class ApplyStat(
    val code: String,
) {
    /** 차감이 반영됐다 */
    APPLIED("A"),

    /** 차감했다가 되돌렸다 */
    REVERTED("R"),

    /** 차감하지 않기로 확정했다. 이후의 차감 시도는 거부된다 */
    REJECTED("X"),
    ;

    companion object {
        fun fromCode(code: String): ApplyStat = entries.first { it.code == code }
    }
}

sealed interface ReserveResult {
    /** 이번 호출로 차감했다 */
    data object Reserved : ReserveResult

    /** 조건에 맞지 않아 차감하지 않았다. 레거시에는 아무 흔적도 남지 않는다 */
    data class Rejected(
        val reason: ReserveFailReason,
    ) : ReserveResult

    /** 이 주문은 이전 호출에서 이미 결론이 났다. 이번 호출은 아무것도 바꾸지 않았다 */
    data class AlreadyDecided(
        val stat: ApplyStat,
    ) : ReserveResult
}

enum class ReserveFailReason {
    GOODS_NOT_FOUND,
    GOODS_NOT_ON_SALE,
    PRICE_CHANGED,
    OUT_OF_STOCK,
    COUPON_UNAVAILABLE,
    POINT_INSUFFICIENT,
}

enum class ReleaseResult {
    /** 이번 호출로 되돌렸다 */
    RELEASED,

    /** 이전 호출에서 이미 되돌렸다 */
    ALREADY_RELEASED,

    /** 차감된 적이 없어 되돌릴 것이 없다 */
    NOT_APPLIED,
}
