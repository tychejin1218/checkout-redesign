package com.example.checkout.legacy.mapper

import org.apache.ibatis.annotations.Mapper

/**
 * 레거시 DB 연결 확인용 매퍼. SQL 은 `resources/mapper/legacy/LegacyConnectionMapper.xml`
 */
@Mapper
interface LegacyConnectionMapper {
    fun countGoods(): Int

    fun countOrders(): Int

    fun countTablesByName(tableName: String): Int

    fun findStockQty(goodsCd: String): Int?

    fun updateStockQty(
        goodsCd: String,
        stockQty: Int,
    ): Int
}
