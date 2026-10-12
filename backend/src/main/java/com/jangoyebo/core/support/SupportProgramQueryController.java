package com.jangoyebo.core.support;

import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.response.PageResponse;
import com.jangoyebo.core.support.SupportProgramQueryDtos.Detail;
import com.jangoyebo.core.support.SupportProgramQueryDtos.ListItem;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 지원 제도 검색·상세 (API 17.1 · 17.2, P1-15).
 *
 * <p><b>사업장 하위 경로가 아니다.</b> 공고는 사업장 공용 데이터라 누가 보든 같은 목록이고,
 * 그래서 {@code /businesses/{businessId}} 아래 두지 않는다. 맞춤 추천은 사업장이 필요하므로
 * 17.3 이 따로 있다.
 *
 * <p>인증은 필요하다 — 공통규약 2.1 이 {@code /auth/**} 와 {@code GET /bank-codes} 만 열어 뒀다.
 */
@RestController
@RequestMapping("/api/v1/support-programs")
public class SupportProgramQueryController {

    /** 명세 2.3 — 기본 20, 최대 100. */
    private static final int MAX_SIZE = 100;

    private final SupportProgramQueryService queryService;

    public SupportProgramQueryController(SupportProgramQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 공고 검색 (17.1).
     *
     * <p>{@code q} 는 <b>키워드 검색이다.</b> 명세가 말한 자연어 임베딩 검색(RAG)은 별도
     * 스토리로 뺐다 — 지금은 공고명과 주관 기관에서 찾는다. 상세요강까지 훑으려면 임베딩
     * 인덱스가 필요하고, 그것 없이 원문 전체를 {@code ILIKE} 로 훑으면 공고가 쌓일수록
     * 느려지면서 정확도는 안 오른다.
     */
    @GetMapping
    public ApiResponse<PageResponse<ListItem>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String industryType,
            @RequestParam(required = false) String supportType,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deadlineAfter,
            @RequestParam(required = false, defaultValue = "false") boolean includeClosed,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {

        return ApiResponse.ok(queryService.search(q, region, industryType, supportType,
                deadlineAfter, includeClosed, sort, Math.max(page, 0),
                Math.clamp(size, 1, MAX_SIZE)));
    }

    /** 공고 상세 (17.2). */
    @GetMapping("/{programId}")
    public ApiResponse<Detail> detail(@PathVariable Long programId) {
        return ApiResponse.ok(queryService.detail(programId));
    }
}
