package com.example.checkout.legacy.model

/**
 * 레거시 TB_GOODS 한 행 중 주문에 필요한 컬럼
 */
data class LegacyGoods(
    val goodsCd: String,
    val goodsNm: String,
    val salePrc: Long,
    val stockQty: Int,
    val saleStat: String,
) {
    val isOnSale: Boolean
        get() = saleStat == SALE_STAT_ON_SALE

    companion object {
        /** '01' 판매중 / '02' 품절 / '03' 판매중지 */
        const val SALE_STAT_ON_SALE = "01"
    }
}
