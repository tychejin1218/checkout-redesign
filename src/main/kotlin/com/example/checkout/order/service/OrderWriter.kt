package com.example.checkout.order.service

import com.example.checkout.config.NewTransactional
import com.example.checkout.domain.entity.CheckoutOrder
import com.example.checkout.domain.entity.OrderItem
import com.example.checkout.domain.entity.OrderStatus
import com.example.checkout.domain.repository.OrderRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * 신규 DB 의 주문 쓰기. 메서드 하나가 신규 DB 트랜잭션 하나다.
 *
 * 레거시 DB 작업과 한 트랜잭션으로 묶이지 않으므로, 호출하는 쪽은 각 메서드가 끝날 때마다 커밋됐다고 보고 다음 단계를 진행한다.
 */
@Component
class OrderWriter(
    private val entityManager: EntityManager,
    private val orderRepository: OrderRepository,
    private val clock: Clock,
) {
    /**
     * 주문과 품목을 INSERT 한다.
     *
     * `Repository.save` 를 쓰지 않는다. PK 를 직접 넣는 엔티티에 `save` 는 merge 로 동작해서, 주문번호가 이미 있으면
     * 그 주문을 덮어쓴다. `persist` 는 항상 INSERT 이므로 주문번호나 멱등 키가 겹치면 제약 위반으로 실패한다.
     */
    @NewTransactional
    fun createPending(
        order: CheckoutOrder,
        items: List<OrderItem>,
    ) {
        entityManager.persist(order)
        items.forEach { entityManager.persist(it) }
    }

    /** 현재 상태가 [from] 일 때만 [to] 로 바꾼다. 이미 다른 쪽이 바꿨으면 false */
    @NewTransactional
    fun transition(
        orderNo: String,
        from: OrderStatus,
        to: OrderStatus,
        failReason: String? = null,
    ): Boolean = orderRepository.transition(orderNo, from, to, failReason, clock.millis()) == 1
}
