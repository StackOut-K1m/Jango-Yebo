package com.jangoyebo.core.support;

import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.core.support.DocumentGuideDtos.Guide;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 서류 발급처 사전 (API 17.4, P1-15).
 *
 * <p><b>사업장 하위 경로가 아니다.</b> 「사업자등록증명을 어디서 떼나」는 사업장마다 다르지
 * 않은 기준 데이터다 — {@code /tax-filing-forms}(14.3)와 같은 자리에 둔다.
 *
 * <p><b>발급 자동화는 범위 밖이다</b>(17.4). 대리 발급은 하지 않고 어디서 어떻게 떼는지만
 * 알려 준다.
 *
 * <p>인증은 필요하다 — 공통규약 2.1 이 {@code /auth/**} 와 {@code GET /bank-codes} 만 열어 뒀다.
 */
@RestController
@RequestMapping("/api/v1/document-guides")
public class DocumentGuideController {

    private final DocumentGuideService guideService;

    public DocumentGuideController(DocumentGuideService guideService) {
        this.guideService = guideService;
    }

    /**
     * 사전 조회.
     *
     * @param programId 있으면 그 공고가 요구하는 서류만. 없는 공고·구조화 전 공고는
     *                  17.2 와 같이 {@code 404 PROGRAM_NOT_FOUND}
     * @param q         서류명·발급처·별칭 부분 일치
     */
    @GetMapping
    public ApiResponse<Map<String, List<Guide>>> list(
            @RequestParam(required = false) Long programId,
            @RequestParam(required = false) String q) {

        return ApiResponse.ok(Map.of("items", guideService.list(programId, q)));
    }
}
