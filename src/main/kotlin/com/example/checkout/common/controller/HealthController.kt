package com.example.checkout.common.controller

import com.example.checkout.common.response.BaseResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 헬스 체크
 */
@RestController
class HealthController {
    @GetMapping("/", "/health")
    fun health(): BaseResponse<Unit> = BaseResponse.ok()
}
