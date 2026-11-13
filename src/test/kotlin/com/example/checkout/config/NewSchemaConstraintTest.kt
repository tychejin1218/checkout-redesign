package com.example.checkout.config

import com.example.checkout.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * 신규 DB 스키마의 제약이 실제로 걸려 있는지 확인한다. 애플리케이션 코드를 거치지 않고 SQL 로 직접 넣어 본다.
 */
class NewSchemaConstraintTest(
    @Autowired @Qualifier("newDataSource") newDataSource: DataSource,
    @Autowired private val databaseResetter: DatabaseResetter,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(newDataSource)

    @Test
    fun `주문이 없는 품목은 넣을 수 없다`() {
        assertThatThrownBy { insertItem("NO-SUCH-ORDER") }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `품목이 있는 주문은 지울 수 없다`() {
        insertOrder("N1", memberNo = 1001, idempotencyKey = "key-1")
        insertItem("N1")

        assertThatThrownBy { jdbc.update("DELETE FROM orders WHERE order_no = 'N1'") }
            .isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `품목이 있는 주문이 남아 있어도 DB 를 초기화할 수 있다`() {
        insertOrder("N1", memberNo = 1001, idempotencyKey = "key-1")
        insertItem("N1")

        databaseResetter.resetAll()

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Int::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item", Int::class.java)).isZero()
    }

    @Test
    fun `같은 회원은 같은 멱등 키로 주문을 두 번 넣을 수 없다`() {
        insertOrder("N1", memberNo = 1001, idempotencyKey = "key-1")

        assertThatThrownBy { insertOrder("N2", memberNo = 1001, idempotencyKey = "key-1") }
            .isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `다른 회원은 같은 멱등 키를 쓸 수 있다`() {
        insertOrder("N1", memberNo = 1001, idempotencyKey = "key-1")
        insertOrder("N2", memberNo = 1002, idempotencyKey = "key-1")

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Int::class.java)).isEqualTo(2)
    }

    @Test
    fun `상품 금액에서 할인을 뺀 값이 결제 금액과 다르면 넣을 수 없다`() {
        assertThatThrownBy { insertOrder("N1", memberNo = 1001, idempotencyKey = "key-1", payAmount = 9_999) }
            .isInstanceOf(DataAccessException::class.java)
    }

    private fun insertOrder(
        orderNo: String,
        memberNo: Long,
        idempotencyKey: String,
        payAmount: Long = 10_000,
    ) {
        jdbc.update(
            """
            INSERT INTO orders (order_no, member_no, status, item_amount, discount_amount, pay_amount, point_amount,
                                idempotency_key, created_at, updated_at)
            VALUES (?, ?, 'PENDING', 10000, 0, ?, 0, ?, 0, 0)
            """.trimIndent(),
            orderNo,
            memberNo,
            payAmount,
            idempotencyKey,
        )
    }

    private fun insertItem(orderNo: String) {
        jdbc.update(
            """
            INSERT INTO order_item (order_no, seq, goods_cd, goods_name, qty, unit_price)
            VALUES (?, 1, 'G001', 'x', 1, 10000)
            """.trimIndent(),
            orderNo,
        )
    }
}
