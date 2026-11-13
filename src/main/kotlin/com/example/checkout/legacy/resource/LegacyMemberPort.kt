package com.example.checkout.legacy.resource

import com.example.checkout.legacy.mapper.LegacyMemberMapper
import org.springframework.stereotype.Component

/**
 * 레거시 DB 에 있는 회원을 신규 시스템이 읽는 통로. 회원의 원본은 레거시 TB_MEMBER 이고 신규 시스템은 읽기만 한다
 */
interface LegacyMemberPort {
    /**
     * 주문할 수 있는 회원인지. 레거시 TB_MEMBER 에 있고 USE_YN 이 'Y' 여야 한다.
     *
     * USE_YN = 'N' 은 탈퇴 또는 휴면으로 본다(DESIGN.md 9장 가정 13)
     */
    fun isActive(memberNo: Long): Boolean
}

@Component
class LegacyMemberAdapter(
    private val mapper: LegacyMemberMapper,
) : LegacyMemberPort {
    override fun isActive(memberNo: Long): Boolean = mapper.countActiveMember(memberNo) > 0
}
