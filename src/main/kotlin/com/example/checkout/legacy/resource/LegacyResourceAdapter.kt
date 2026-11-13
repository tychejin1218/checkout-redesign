package com.example.checkout.legacy.resource

import com.example.checkout.config.LegacyDataSourceConfig
import com.example.checkout.legacy.LegacyTime
import com.example.checkout.legacy.mapper.LegacyResourceMapper
import com.example.checkout.legacy.model.LegacyCoupon
import com.example.checkout.legacy.model.LegacyGoods
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * 레거시 자원 포트의 MyBatis 구현
 *
 * 확보·반환은 각각 레거시 트랜잭션 하나다. 반영 기록(TB_NEW_ORD_APPLY)을 자원 변경과 같은 트랜잭션에서 기록하므로
 * "반영 기록이 있다" 와 "자원이 바뀌었다" 가 항상 함께 참이거나 함께 거짓이다.
 *
 * 조건에 맞지 않을 때 예외 없이 롤백하고 결과를 돌려줘야 해서 어노테이션 대신 TransactionTemplate 을 쓴다.
 */
@Component
class LegacyResourceAdapter(
    private val mapper: LegacyResourceMapper,
    @Qualifier(LegacyDataSourceConfig.TRANSACTION_MANAGER) transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) : LegacyResourcePort {
    private val legacyTransaction = TransactionTemplate(transactionManager)

    override fun findGoods(goodsCd: String): LegacyGoods? = mapper.findGoods(goodsCd)

    override fun findCoupon(couponNo: String): LegacyCoupon? = mapper.findCoupon(couponNo)

    override fun reserve(resources: OrderResources): ReserveResult =
        legacyTransaction.execute<ReserveResult> { status ->
            // 반영 기록을 가장 먼저 넣는다. 이미 있으면 이 주문은 결론이 난 것이므로 아무것도 바꾸지 않는다
            if (mapper.insertApplyMarker(
                    resources.orderNo,
                    ApplyStat.APPLIED.code,
                    LegacyTime.format(clock.instant()),
                ) ==
                0
            ) {
                return@execute ReserveResult.AlreadyDecided(currentStat(resources.orderNo))
            }
            val failReason = deductStocks(resources) ?: useCoupon(resources) ?: deductPoint(resources)
            if (failReason == null) {
                ReserveResult.Reserved
            } else {
                // 반영 기록을 포함해 이 트랜잭션에서 바꾼 것을 모두 되돌린다
                status.setRollbackOnly()
                ReserveResult.Rejected(failReason)
            }
        }

    override fun release(resources: OrderResources): ReleaseResult =
        legacyTransaction.execute<ReleaseResult> {
            // 'A' → 'R' 전이는 한 번만 성공한다. 성공한 호출만 자원을 되돌린다
            if (mapper.markReverted(resources.orderNo, LegacyTime.format(clock.instant())) == 1) {
                restore(resources)
                ReleaseResult.RELEASED
            } else if (mapper.findApplyStat(resources.orderNo) == ApplyStat.REVERTED.code) {
                ReleaseResult.ALREADY_RELEASED
            } else {
                ReleaseResult.NOT_APPLIED
            }
        }

    override fun rejectIfUndecided(orderNo: String): ApplyStat =
        legacyTransaction.execute<ApplyStat> {
            // 반영 기록이 없을 때만 들어간다. 이미 'A' 로 반영됐다면 그대로 두고 그 값을 돌려준다
            mapper.insertApplyMarker(orderNo, ApplyStat.REJECTED.code, LegacyTime.format(clock.instant()))
            currentStat(orderNo)
        }

    /** 상품 코드 순으로 차감한다. 차감하지 못한 첫 상품의 이유를 돌려주고, 모두 차감했으면 null */
    private fun deductStocks(resources: OrderResources): ReserveFailReason? =
        resources.lines
            .sortedBy { it.goodsCd }
            .firstNotNullOfOrNull { line ->
                val deducted = mapper.deductStock(line.goodsCd, line.qty, line.unitPrice) == 1
                if (deducted) null else stockFailReason(line)
            }

    private fun stockFailReason(line: OrderResources.Line): ReserveFailReason {
        val goods = mapper.findGoods(line.goodsCd)
        return when {
            goods == null -> ReserveFailReason.GOODS_NOT_FOUND
            !goods.isOnSale -> ReserveFailReason.GOODS_NOT_ON_SALE
            goods.salePrc != line.unitPrice -> ReserveFailReason.PRICE_CHANGED
            else -> ReserveFailReason.OUT_OF_STOCK
        }
    }

    private fun useCoupon(resources: OrderResources): ReserveFailReason? {
        val couponNo = resources.couponNo ?: return null
        val used = mapper.useCoupon(couponNo, resources.memberNo, resources.orderNo) == 1
        return if (used) null else ReserveFailReason.COUPON_UNAVAILABLE
    }

    private fun deductPoint(resources: OrderResources): ReserveFailReason? {
        if (resources.pointAmount == 0L) return null
        val deducted = mapper.deductPoint(resources.memberNo, resources.pointAmount) == 1
        return if (deducted) null else ReserveFailReason.POINT_INSUFFICIENT
    }

    private fun restore(resources: OrderResources) {
        resources.lines.forEach { mapper.restoreStock(it.goodsCd, it.qty) }
        resources.couponNo?.let { mapper.restoreCoupon(it, resources.orderNo) }
        if (resources.pointAmount > 0) mapper.restorePoint(resources.memberNo, resources.pointAmount)
    }

    private fun currentStat(orderNo: String): ApplyStat =
        ApplyStat.fromCode(checkNotNull(mapper.findApplyStat(orderNo)) { "반영 기록이 없습니다: $orderNo" })
}
