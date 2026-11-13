package com.example.checkout.support

import com.example.checkout.config.DatabaseResetter
import com.example.checkout.order.service.OrderNoGenerator
import com.example.checkout.order.service.OrderStepObserver
import com.ninjasquad.springmockk.MockkBean
import com.ninjasquad.springmockk.MockkSpyBean
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import javax.sql.DataSource

/**
 * 신규 DB 와 레거시 DB 를 실제 SQLite 파일로 쓰는 통합 테스트 베이스
 *
 * 테스트마다 두 DB 를 초기화 스크립트 직후 상태로 되돌린다. 트랜잭션 롤백에 기대지 않으므로
 * 테스트가 커밋한 데이터나 여러 커넥션으로 쓴 데이터도 다음 테스트에 남지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
abstract class IntegrationTest {
    /**
     * 두 DB 의 커밋 사이 지점. 기본은 아무것도 하지 않고, 테스트가 여기서 예외를 던져 프로세스 중단을 재현한다.
     * 모든 하위 테스트가 같은 목을 써야 스프링 컨텍스트를 재사용한다.
     */
    @MockkBean(relaxed = true)
    protected lateinit var orderStepObserver: OrderStepObserver

    /** 실제 생성기를 그대로 쓰되, 주문번호가 겹치는 상황을 만들 때만 테스트가 반환값을 정한다 */
    @MockkSpyBean
    protected lateinit var orderNoGenerator: OrderNoGenerator

    @Autowired
    private lateinit var databaseResetter: DatabaseResetter

    @Autowired
    @Qualifier("legacyDataSource")
    private lateinit var legacyDataSource: DataSource

    /** 레거시 DB 의 상태를 애플리케이션 코드를 거치지 않고 직접 확인한다 */
    protected val legacyJdbc: JdbcTemplate by lazy { JdbcTemplate(legacyDataSource) }

    @BeforeEach
    fun resetDatabases() {
        databaseResetter.resetAll()
    }

    protected fun stockOf(goodsCd: String): Int =
        legacyJdbc.queryForObject("SELECT STOCK_QTY FROM TB_GOODS WHERE GOODS_CD = ?", Int::class.java, goodsCd) ?: -1

    protected fun pointOf(memberNo: Long): Long =
        legacyJdbc.queryForObject("SELECT POINT_AMT FROM TB_MEMBER WHERE MBR_NO = ?", Long::class.java, memberNo) ?: -1

    /** 쿠폰의 (사용 여부, 사용된 주문번호) */
    protected fun couponUseOf(couponNo: String): Pair<String, String?> =
        legacyJdbc
            .query("SELECT USE_YN, USE_ORD_NO FROM TB_COUPON WHERE CPN_NO = ?", { rs, _ ->
                rs.getString(1) to rs.getString(2)
            }, couponNo)
            .single()

    /** 레거시 반영 기록(TB_NEW_ORD_APPLY.APPLY_STAT). 없으면 null */
    protected fun markerOf(orderNo: String): String? =
        legacyJdbc
            .queryForList("SELECT APPLY_STAT FROM TB_NEW_ORD_APPLY WHERE ORD_NO = ?", String::class.java, orderNo)
            .firstOrNull()

    protected fun markerCount(stat: String): Int =
        legacyJdbc.queryForObject(
            "SELECT COUNT(*) FROM TB_NEW_ORD_APPLY WHERE APPLY_STAT = ?",
            Int::class.java,
            stat,
        ) ?: -1

    /** 레거시 초기 데이터의 쿠폰은 모두 사용됐으므로 미사용 쿠폰을 새로 넣는다 */
    protected fun issueCoupon(
        couponNo: String,
        memberNo: Long,
        discountAmount: Long,
    ) {
        legacyJdbc.update(
            "INSERT INTO TB_COUPON (CPN_NO, MBR_NO, CPN_NM, DC_AMT, USE_YN, REG_DT) VALUES (?, ?, '테스트 쿠폰', ?, 'N', ?)",
            couponNo,
            memberNo,
            discountAmount,
            "20260301000000",
        )
    }
}

/** 단계 사이에서 프로세스가 죽은 것을 재현하는 예외 */
class SimulatedCrash : RuntimeException("프로세스 중단")
