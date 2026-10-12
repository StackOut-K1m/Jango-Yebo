package com.jangoyebo.core.fin;

import com.jangoyebo.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 은행 목록 (API 7.2). 계좌 등록 화면(SC-01-4)의 은행 셀렉트가 이걸 쓴다.
 *
 * <p><b>인증이 없다</b>(공통규약 2.1) — 로그인 전 온보딩에서 부르기 때문이다.
 * 그래서 {@link BankCodeService} 의 캐시가 이 경로의 유일한 보호 장치다.
 */
@RestController
@RequestMapping("/api/v1/bank-codes")
public class BankCodeController {

    private final BankCodeService bankCodeService;

    public BankCodeController(BankCodeService bankCodeService) {
        this.bankCodeService = bankCodeService;
    }

    @GetMapping
    public ApiResponse<Map<String, List<BankCode>>> list() {
        return ApiResponse.ok(Map.of("items", bankCodeService.findAll()));
    }
}
