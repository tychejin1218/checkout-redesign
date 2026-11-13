package com.example.checkout.order.service

import com.example.checkout.domain.entity.CheckoutOrder
import com.example.checkout.domain.entity.OrderItem
import com.example.checkout.legacy.resource.OrderResources

/** 레거시에서 확보·반환할 자원을 신규 DB 의 주문 스냅샷에서 만든다 */
fun CheckoutOrder.toResources(items: List<OrderItem>): OrderResources =
    OrderResources(
        orderNo = orderNo,
        memberNo = memberNo,
        lines = items.map { OrderResources.Line(it.goodsCd, it.qty, it.unitPrice) },
        couponNo = couponNo,
        pointAmount = pointAmount,
    )
