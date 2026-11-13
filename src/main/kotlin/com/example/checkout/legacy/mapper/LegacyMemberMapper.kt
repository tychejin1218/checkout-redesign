package com.example.checkout.legacy.mapper

import org.apache.ibatis.annotations.Mapper

/**
 * 레거시 DB 의 회원 조회. SQL 은 `resources/mapper/legacy/LegacyMemberMapper.xml`
 */
@Mapper
interface LegacyMemberMapper {
    /** 회원 번호가 TB_MEMBER 에 있고 USE_YN 이 'Y' 이면 1, 아니면 0 */
    fun countActiveMember(mbrNo: Long): Int
}
