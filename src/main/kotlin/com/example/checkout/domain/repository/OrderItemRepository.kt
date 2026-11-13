package com.example.checkout.domain.repository

import com.example.checkout.domain.entity.OrderItem
import com.example.checkout.domain.entity.OrderItemId
import org.springframework.data.jpa.repository.JpaRepository

interface OrderItemRepository : JpaRepository<OrderItem, OrderItemId> {
    fun findByIdOrderNoOrderByIdSeq(orderNo: String): List<OrderItem>
}
