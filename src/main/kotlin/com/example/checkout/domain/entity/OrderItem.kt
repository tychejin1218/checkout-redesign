package com.example.checkout.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.util.Objects

/**
 * 주문 품목 (신규 DB `order_item`). 단가와 상품명은 주문 시점 값이다
 */
@Entity
@Table(name = "order_item")
class OrderItem(
    @EmbeddedId
    val id: OrderItemId,
    @Column(name = "goods_cd", nullable = false)
    val goodsCd: String,
    @Column(name = "goods_name", nullable = false)
    val goodsName: String,
    @Column(name = "qty", nullable = false)
    val qty: Int,
    @Column(name = "unit_price", nullable = false)
    val unitPrice: Int,
)

/**
 * 주문 품목 PK (`order_no`, `seq`)
 */
@Embeddable
class OrderItemId(
    @Column(name = "order_no")
    val orderNo: String,
    @Column(name = "seq")
    val seq: Int,
) : Serializable {
    override fun equals(other: Any?): Boolean = other is OrderItemId && orderNo == other.orderNo && seq == other.seq

    override fun hashCode(): Int = Objects.hash(orderNo, seq)

    companion object {
        private const val serialVersionUID = 1L
    }
}
