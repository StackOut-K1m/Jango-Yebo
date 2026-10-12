package com.jangoyebo.core.support;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.response.PageResponse;
import com.jangoyebo.core.support.SupportProgramQueryDtos.Detail;
import com.jangoyebo.core.support.SupportProgramQueryDtos.ListItem;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 공고 검색·상세 (17.1 · 17.2).
 *
 * <p>여기서 <b>자격을 판정하지 않는다.</b> 조건에 맞을 「가능성이 있는」 공고를 추려 줄 뿐이고,
 * 최종 판단은 주관 기관이 한다 — 화면이 그 문구를 고정 노출한다(17.1).
 */
@Service
public class SupportProgramQueryService {

    /** 17.1 {@code supportType} 필터 값. CHECK 과 같은 집합이다. */
    private static final Set<String> SUPPORT_TYPES =
            Set.of("FUNDING", "SUBSIDY", "VOUCHER", "CONSULTING", "OTHER");

    /**
     * 17.1 {@code sort} 중 <b>구현한 둘.</b>
     *
     * <p>명세의 {@code MATCH_SCORE} 는 여기서 낼 수 없다 — 이 경로에 사업장이 없어 점수의
     * 입력(지역·업종·매출·업력)이 없다. <b>조용히 다른 순서로 주지 않고 거절한다</b>:
     * 정렬됐다고 믿은 화면이 「가장 잘 맞는 것」을 맨 위로 보여 주면 근거 없는 추천이 된다.
     */
    private static final Set<String> SORTS = Set.of("DEADLINE", "COLLECTED_AT");

    private final SupportProgramSearchRepository search;
    private final SupportProgramRepository programs;
    private final DocumentGuideService documentGuides;

    public SupportProgramQueryService(SupportProgramSearchRepository search,
                                      SupportProgramRepository programs,
                                      DocumentGuideService documentGuides) {
        this.search = search;
        this.programs = programs;
        this.documentGuides = documentGuides;
    }

    @Transactional(readOnly = true)
    public PageResponse<ListItem> search(String q, String region, String industryType,
                                         String supportType, LocalDate deadlineAfter,
                                         boolean includeClosed, String sort,
                                         int page, int size) {
        String resolvedSort = requireSort(sort);
        requireSupportType(supportType);

        var result = search.search(blankToNull(q), blankToNull(region), broadestRegion(region),
                blankToNull(industryType), blankToNull(supportType), deadlineAfter,
                includeClosed, resolvedSort, PageRequest.of(page, size));

        return PageResponse.from(result, ListItem::from);
    }

    /**
     * 상세 (17.2).
     *
     * <p><b>구조화되지 않은 공고는 404 다.</b> 목록에 안 나오는 것을 상세에서만 볼 수 있으면
     * 「자격 조건을 모르는 공고」가 링크 하나로 사용자에게 도달한다.
     *
     * <p>제출 서류에는 <b>서류 사전(17.4)을 붙여</b> 발급 링크와 수수료를 채운다. 워커가 그
     * 두 칸을 비워 보내기 때문이다 — 사전이 없으면 17.2 는 「무슨 서류가 필요한지」까지만
     * 말하고 「어디서 떼는지」는 사장님이 알아서 찾아야 한다.
     */
    @Transactional(readOnly = true)
    public Detail detail(Long programId) {
        SupportProgram program = programs.findById(programId)
                .filter(SupportProgram::isStructured)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROGRAM_NOT_FOUND));

        Object stored = program.getEligibility().get("requiredDocuments");
        return Detail.from(program, documentGuides.attachGuides(
                stored instanceof List<?> list ? list : List.of()));
    }

    private String requireSort(String sort) {
        if (sort == null || sort.isBlank()) {
            // 기본은 마감 임박순이다. 사장님이 목록을 여는 이유가 「지금 신청할 수 있는 것」
            // 이고, 부분 인덱스(ix_sp_open_deadline)도 이 순서를 받친다.
            return "DEADLINE";
        }
        if ("MATCH_SCORE".equals(sort)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "맞춤 점수 정렬은 사업장이 필요합니다. "
                            + "GET /businesses/{businessId}/support-programs/recommendations 를 쓰세요.");
        }
        if (!SORTS.contains(sort)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "정렬은 DEADLINE · COLLECTED_AT 중 하나입니다.");
        }
        return sort;
    }

    /** 모르는 값을 조용히 무시하면 「그 종류가 하나도 없다」로 읽힌다. */
    private void requireSupportType(String supportType) {
        if (supportType != null && !supportType.isBlank()
                && !SUPPORT_TYPES.contains(supportType)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "지원 종류는 FUNDING · SUBSIDY · VOUCHER · CONSULTING · OTHER 중 하나입니다.");
        }
    }

    /**
     * 지역을 <b>가장 넓은 단위 한 토막</b>으로 줄인다 — {@code "광주 서구"} → {@code "광주"}.
     *
     * <p>사업장 주소는 구·동까지 내려가는데 <b>공고는 그 단위로 지역을 적지 않는다.</b> 그대로
     * 대조하면 「광주 서구」 사장님에게 「광주」 공고가 안 보인다 — 자격이 되는데 있었다는
     * 사실조차 모르는, 이 화면에서 가장 나쁜 실패다.
     *
     * <p>기업마당 태그가 「전남광주」처럼 두 지역을 묶어 오는 것도 이걸로 함께 걸린다.
     * 반대로 넓혀서 생기는 손해는 <b>안 맞는 공고가 몇 줄 더 보이는 것</b>뿐이고, 최종 판단은
     * 어차피 기관 확인이다(17.1).
     */
    private String broadestRegion(String region) {
        String value = blankToNull(region);
        if (value == null) {
            return null;
        }
        String[] tokens = value.trim().split("\\s+");
        return tokens[0];
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
