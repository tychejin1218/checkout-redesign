package com.example.checkout.order

import com.example.checkout.order.dto.OrderLine
import com.example.checkout.order.dto.PlaceOrderCommand
import java.util.UUID

/*
 * 레거시 초기 데이터 (legacy_db.sql)
 *   상품  G001 28,000원 재고 50 / G002 36,000원 재고 3 / G003 품절 / G004 40,000원 재고 120
 *   회원  1001 포인트 5,000 / 1002 포인트 120,000 / 1003 포인트 0
 */
const val G001 = "G001"
const val G002 = "G002"
const val G001_PRICE = 28_000L
const val G002_PRICE = 36_000L
const val G001_STOCK = 50
const val G002_STOCK = 3
const val MEMBER_1001 = 1001L
const val MEMBER_1002 = 1002L
const val MEMBER_1002_POINT = 120_000L

/** legacy_db.sql 에 없는 회원 번호 */
const val UNKNOWN_MEMBER = 9_999L

/** 쿠폰·포인트 없이 상품 하나를 주문하는 요청. 결제 금액은 현재 가격으로 맞춰 둔다 */
fun orderCommand(
    goodsCd: String = G001,
    qty: Int = 1,
    unitPrice: Long = G001_PRICE,
    memberNo: Long = MEMBER_1002,
    idempotencyKey: String = UUID.randomUUID().toString(),
): PlaceOrderCommand =
    PlaceOrderCommand(
        memberNo = memberNo,
        lines = listOf(OrderLine(goodsCd, qty)),
        expectedPayAmount = unitPrice * qty,
        idempotencyKey = idempotencyKey,
    )
