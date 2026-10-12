package com.jangoyebo.core.tax;

import com.jangoyebo.common.response.ApiResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 종합소득세 신고서 서식 템플릿 조회 (API 14.3).
 *
 * <p>화면이 "어느 서식으로 초안을 만들지" 고르는 데 쓴다. 기준 데이터라 사업장이 없고,
 * 귀속연도만 받는다.
 *
 * <p><b>초안을 못 만드는 서식도 돌려준다.</b> {@code supportLevel} 이 {@code GUIDE_ONLY}·
 * {@code CHECKLIST_ONLY} 인 둘은 항목을 채우지 않지만, 사장님이 자기 유형을 골랐을 때
 * "이 서식은 안내만 한다"를 화면이 말해 줄 수 있어야 한다. 목록에서 빼면 고르는 자리에
 * 아예 안 나와서 <b>지원하지 않는 것인지 빠뜨린 것인지</b> 구분되지 않는다.
 */
@RestController
@RequestMapping("/api/v1/tax-filing-forms")
public class TaxFilingFormController {

    private final TaxFormTemplateRepository repository;

    public TaxFilingFormController(TaxFormTemplateRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, List<FormResponse>>> list(@RequestParam int taxYear) {
        List<FormResponse> items = repository.findByTaxYearAndActiveTrueOrderByIdAsc(taxYear)
                .stream()
                .map(FormResponse::of)
                .toList();
        return ApiResponse.ok(Map.of("items", items));
    }

    /**
     * 서식 한 벌.
     *
     * @param sections 항목 정의. <b>안내만 하는 서식은 키 자체가 없다</b> — 빈 배열로 내보내면
     *                 화면이 "항목이 0개인 서식"으로 읽고 빈 표를 그린다
     */
    public record FormResponse(String formType,
                               String name,
                               String officialForm,
                               String supportLevel,
                               String eligibilityNote,
                               List<Map<String, Object>> sections) {

        static FormResponse of(TaxFormTemplate template) {
            List<Map<String, Object>> sections = template.getSections();
            return new FormResponse(
                    template.getFormType(),
                    template.getName(),
                    template.getOfficialForm(),
                    template.getSupportLevel(),
                    template.getEligibilityNote(),
                    sections.isEmpty() ? null : sections);
        }
    }
}
