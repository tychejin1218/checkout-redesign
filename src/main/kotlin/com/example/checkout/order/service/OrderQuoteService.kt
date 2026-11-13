package com.example.checkout.order.service

import com.example.checkout.common.exception.ApiException
import com.example.checkout.common.type.ApiStatus
import com.example.checkout.legacy.resource.LegacyMemberPort
import com.example.checkout.legacy.resource.LegacyResourcePort
import com.example.checkout.order.dto.OrderLine
import com.example.checkout.order.dto.PlaceOrderCommand
import org.springframework.stereotype.Service

/**
 * 주문 요청 검증과 금액 계산. 가격의 원본은 레거시 TB_GOODS.SALE_PRC 이고 주문 시점에 서버가 읽어 확정한다.
 *
 * 여기서 읽은 가격은 확보 단계의 차감 조건(`SALE_PRC = ?`)으로 다시 확인된다. 그 사이에 가격이 바뀌면 차감이 실패한다.
 */
@Service
class OrderQuoteService(
    private val legacyResourcePort: LegacyResourcePort,
    private val legacyMemberPort: LegacyMemberPort,
) {
    fun quote(command: PlaceOrderCommand): OrderQuote {
        validateMember(command.memberNo)
        validateLines(command)
        val lines = command.lines.map { quoteLine(it) }
        // 수량(Int) × 단가(Long). Int 끼리 곱하면 수량이 클 때 넘친다
        val itemAmount = lines.sumOf { it.qty * it.unitPrice }
        val quote = OrderQuote(lines, itemAmount, discountOf(command, itemAmount))
        validateAmounts(command, quote)
        return quote
    }

    /** 회원의 원본은 레거시 TB_MEMBER 다. 포인트를 쓰지 않는 주문은 확보 단계에서 회원 행을 건드리지 않으므로 여기서 확인한다 */
    private fun validateMember(memberNo: Long) {
        if (!legacyMemberPort.isActive(memberNo)) throw ApiException(ApiStatus.ORDER_MEMBER_UNAVAILABLE)
    }

    private fun quoteLine(line: OrderLine): OrderQuote.Line {
        val goods = legacyResourcePort.findGoods(line.goodsCd)?.takeIf { it.isOnSale }
        goods ?: throw ApiException(ApiStatus.ORDER_GOODS_UNAVAILABLE)
        return OrderQuote.Line(goods.goodsCd, goods.goodsNm, line.qty, goods.salePrc)
    }

    private fun validateAmounts(
        command: PlaceOrderCommand,
        quote: OrderQuote,
    ) {
        if (command.pointAmount !in 0..quote.payAmount) throw ApiException(ApiStatus.ORDER_POINT_INVALID)
        // 사용자가 확인한 금액과 다르면 주문을 받지 않는다. 가격·쿠폰이 화면을 본 뒤에 바뀐 경우다
        if (command.expectedPayAmount != quote.payAmount) throw ApiException(ApiStatus.ORDER_AMOUNT_CHANGED)
    }

    private fun validateLines(command: PlaceOrderCommand) {
        val valid =
            command.lines.isNotEmpty() &&
                command.lines.all { it.qty > 0 } &&
                command.lines.distinctBy { it.goodsCd }.size == command.lines.size
        if (!valid) throw ApiException(ApiStatus.VALIDATION_INVALID_REQUEST)
    }

    /** 쿠폰 할인. 상품 금액을 넘지 않는다 */
    private fun discountOf(
        command: PlaceOrderCommand,
        itemAmount: Long,
    ): Long {
        val couponNo = command.couponNo ?: return 0L
        val coupon = legacyResourcePort.findCoupon(couponNo)?.takeIf { it.isUsableBy(command.memberNo) }
        coupon ?: throw ApiException(ApiStatus.ORDER_COUPON_UNAVAILABLE)
        return minOf(coupon.dcAmt, itemAmount)
    }
}

/**
 * 서버가 확정한 주문 금액과 품목 스냅샷
 */
data class OrderQuote(
    val lines: List<Line>,
    val itemAmount: Long,
    val discountAmount: Long,
) {
    val payAmount: Long
        get() = itemAmount - discountAmount

    data class Line(
        val goodsCd: String,
        val goodsName: String,
        val qty: Int,
        val unitPrice: Long,
    )
}
