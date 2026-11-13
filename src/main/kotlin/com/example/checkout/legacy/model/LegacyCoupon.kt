package com.example.checkout.legacy.model

/**
 * 레거시 TB_COUPON 한 행 중 주문에 필요한 컬럼
 */
data class LegacyCoupon(
    val cpnNo: String,
    val mbrNo: Long,
    val dcAmt: Int,
    val useYn: String,
) {
    fun isUsableBy(memberNo: Long): Boolean = mbrNo == memberNo && useYn == USE_YN_UNUSED

    companion object {
        const val USE_YN_UNUSED = "N"
    }
}
