package com.example.checkout.order.dto

import com.example.checkout.domain.entity.CheckoutOrder
import com.example.checkout.domain.entity.OrderStatus

/**
 * 주문 요청
 *
 * @property expectedPayAmount 사용자가 화면에서 확인한 결제 금액. 서버가 다시 계산한 값과 다르면 주문을 받지 않는다
 * @property idempotencyKey 같은 요청의 재시도를 한 주문으로 묶는 키. 클라이언트가 주문 시도마다 하나 만든다
 */
data class PlaceOrderCommand(
    val memberNo: Long,
    val lines: List<OrderLine>,
    val expectedPayAmount: Int,
    val idempotencyKey: String,
    val couponNo: String? = null,
    val pointAmount: Int = 0,
)

data class OrderLine(
    val goodsCd: String,
    val qty: Int,
)

/**
 * 주문 처리 결과
 *
 * @property status [OrderStatus.PENDING] 이면 아직 결론이 나지 않은 것이다. 복구 작업자가 결론을 낸다
 */
data class OrderResult(
    val orderNo: String,
    val status: OrderStatus,
    val payAmount: Int,
    val failReason: String?,
)

fun CheckoutOrder.toResult(): OrderResult = OrderResult(orderNo, status, payAmount, failReason)
