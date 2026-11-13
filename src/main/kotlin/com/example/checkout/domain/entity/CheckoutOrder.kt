package com.example.checkout.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * 주문 (신규 DB `orders`)
 *
 * 금액과 품목은 주문 시점의 스냅샷이라 바뀌지 않는다. 상태는 엔티티를 고쳐 저장하지 않고
 * `OrderRepository.transition` 의 조건부 UPDATE 로만 바꾼다. 그래서 프로퍼티가 모두 `val` 이다.
 *
 * @property idempotencyKey 같은 요청의 재시도를 한 주문으로 묶는 키. 회원별로 유일하다(`UNIQUE(member_no, idempotency_key)`)
 * @property payAmount 결제할 금액 (`itemAmount - discountAmount`). 포인트로 내는 금액을 포함한다
 * @property createdAt epoch millis (UTC)
 */
@Entity
@Table(name = "orders")
class CheckoutOrder(
    @Id
    @Column(name = "order_no")
    val orderNo: String,
    @Column(name = "member_no", nullable = false)
    val memberNo: Long,
    @Column(name = "item_amount", nullable = false)
    val itemAmount: Long,
    @Column(name = "discount_amount", nullable = false)
    val discountAmount: Long,
    @Column(name = "pay_amount", nullable = false)
    val payAmount: Long,
    @Column(name = "point_amount", nullable = false)
    val pointAmount: Long,
    @Column(name = "coupon_no")
    val couponNo: String?,
    @Column(name = "idempotency_key", nullable = false)
    val idempotencyKey: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Long,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = createdAt,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    val status: OrderStatus = OrderStatus.PENDING,
    @Column(name = "fail_reason")
    val failReason: String? = null,
)
