package com.example.checkout.legacy.mapper

import com.example.checkout.legacy.model.LegacyCoupon
import com.example.checkout.legacy.model.LegacyGoods
import org.apache.ibatis.annotations.Mapper

/**
 * 레거시 DB 의 재고·쿠폰·포인트와 반영 기록. SQL 은 `resources/mapper/legacy/LegacyResourceMapper.xml`
 *
 * 변경 메서드는 모두 조건부 UPDATE 이고 반환값은 영향 행 수다. 0 이면 조건에 맞지 않아 아무것도 바뀌지 않은 것이다.
 */
@Mapper
interface LegacyResourceMapper {
    fun findGoods(goodsCd: String): LegacyGoods?

    fun findCoupon(cpnNo: String): LegacyCoupon?

    /** 이미 반영 기록이 있으면 아무것도 하지 않고 0 을 돌려준다 */
    fun insertApplyMarker(
        ordNo: String,
        applyStat: String,
        now: String,
    ): Int

    fun findApplyStat(ordNo: String): String?

    /** 'A' → 'R'. 'A' 가 아니면 0 */
    fun markReverted(
        ordNo: String,
        now: String,
    ): Int

    /** 판매중이고, 재고가 충분하고, 가격이 주문 시점과 같을 때만 차감한다 */
    fun deductStock(
        goodsCd: String,
        qty: Int,
        expectedPrice: Long,
    ): Int

    fun restoreStock(
        goodsCd: String,
        qty: Int,
    ): Int

    /** 본인 소유의 미사용 쿠폰일 때만 사용 처리한다 */
    fun useCoupon(
        cpnNo: String,
        mbrNo: Long,
        ordNo: String,
    ): Int

    /** 이 주문이 사용한 쿠폰일 때만 되돌린다 */
    fun restoreCoupon(
        cpnNo: String,
        ordNo: String,
    ): Int

    /** 잔액이 충분할 때만 차감한다 */
    fun deductPoint(
        mbrNo: Long,
        amount: Long,
    ): Int

    fun restorePoint(
        mbrNo: Long,
        amount: Long,
    ): Int
}
