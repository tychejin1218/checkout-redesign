package com.example.checkout.domain.repository

import com.example.checkout.domain.entity.CheckoutOrder
import com.example.checkout.domain.entity.OrderStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface OrderRepository : JpaRepository<CheckoutOrder, String> {
    fun findByOrderNo(orderNo: String): CheckoutOrder?

    /** 멱등 키는 회원별로 유일하다. 다른 회원의 주문이 조회되지 않도록 회원 번호를 함께 넣는다 */
    fun findByMemberNoAndIdempotencyKey(
        memberNo: Long,
        idempotencyKey: String,
    ): CheckoutOrder?

    /** 주어진 상태에 [updatedAt] 이전부터 머물러 있는 주문 */
    fun findByStatusInAndUpdatedAtLessThanEqualOrderByUpdatedAt(
        statuses: Collection<OrderStatus>,
        updatedAt: Long,
    ): List<CheckoutOrder>

    /**
     * 상태 전이. 현재 상태가 [from] 일 때만 바뀐다.
     *
     * 정상 흐름과 복구 작업자가 같은 주문을 동시에 진행시켜도 한쪽만 1 을 받는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update CheckoutOrder o
           set o.status = :to, o.failReason = :failReason, o.updatedAt = :now
         where o.orderNo = :orderNo and o.status = :from
        """,
    )
    fun transition(
        orderNo: String,
        from: OrderStatus,
        to: OrderStatus,
        failReason: String?,
        now: Long,
    ): Int
}
