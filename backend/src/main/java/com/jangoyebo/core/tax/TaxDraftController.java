package com.jangoyebo.core.tax;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 종합소득세 신고서 초안 (API 14.3 · P0-12).
 *
 * <p>집계는 {@code TAX_DRAFT_GENERATION} Job 으로 비동기 처리한다. 한 해 거래 전체를
 * 훑으므로 요청-응답 안에서 끝내면 화면이 묶인다.
 */
@RestController
@RequestMapping("/api/v1")
public class TaxDraftController {

    private final TaxDraftService service;
    private final TaxDraftQueryService query;
    private final TaxDraftExportAssembler assembler;
    private final XlsxRenderer renderer;
    private final BusinessGuard guard;

    public TaxDraftController(TaxDraftService service, TaxDraftQueryService query,
                              TaxDraftExportAssembler assembler, XlsxRenderer renderer,
                              BusinessGuard guard) {
        this.service = service;
        this.query = query;
        this.assembler = assembler;
        this.renderer = renderer;
        this.guard = guard;
    }

    @PostMapping("/businesses/{businessId}/tax-drafts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<CreatedResponse> create(@CurrentBusiness Long businessId,
                                               @Valid @RequestBody CreateRequest request) {
        TaxFilingDraft draft = service.request(
                businessId,
                guard.currentUserId(),
                request.taxYear(),
                request.formType(),
                request.periodStart(),
                request.periodEnd(),
                request.hometaxReference());
        return ApiResponse.ok(new CreatedResponse(
                draft.getId(), draft.getStatus().name(), draft.getJobId()));
    }

    /** 초안 상세 (14.3). 민감 항목은 키만 있고 값은 항상 {@code null} 이다. */
    @GetMapping("/tax-drafts/{draftId}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long draftId) {
        return ApiResponse.ok(query.detail(draftId));
    }

    /**
     * 귀속연도별 초안 목록 (14.3).
     *
     * <p>페이징을 두지 않는다 — 한 해에 생기는 초안이 몇 개뿐이고 명세도 그렇게 정했다.
     */
    @GetMapping("/businesses/{businessId}/tax-drafts")
    public ApiResponse<Map<String, List<Map<String, Object>>>> list(
            @CurrentBusiness Long businessId,
            @RequestParam int taxYear) {
        return ApiResponse.ok(Map.of("items", query.list(businessId, taxYear)));
    }

    /**
     * 초안 삭제 (명세 밖 — 구현현황 4장).
     *
     * <p>목록은 있는데 지우는 길이 없어 잘못 만든 초안이 영영 남았다.
     *
     * <p>⚠ <b>화면은 지우기 전에 한 번 묻는 편이 좋다.</b> 마지막 초안을 지우면 재생성이
     * 물려받을 {@code overrides}(사장님이 직접 적은 값)가 함께 사라진다.
     *
     * <p>집계 중이면 {@code 409 TAX_DRAFT_NOT_DELETABLE} 이다 — 몇 초 뒤면 끝난다.
     */
    @DeleteMapping("/tax-drafts/{draftId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long draftId) {
        service.delete(draftId);
    }

    /**
     * 항목 수정·참고값 입력 (14.3).
     *
     * <p><b>고친 결과를 그대로 돌려준다.</b> 산식이 다시 풀리므로 ㉛ 감가상각비를 적으면
     * 소득금액이 그 자리에서 달라진다 — 화면이 한 번 더 물어보지 않아도 된다.
     */
    @PatchMapping("/tax-drafts/{draftId}")
    public ApiResponse<Map<String, Object>> patch(@PathVariable Long draftId,
                                                  @Valid @RequestBody PatchRequest request) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        for (OverrideEntry entry : request.overridesOrEmpty()) {
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("value", entry.value());
            if (entry.note() != null) {
                patch.put("note", entry.note());
            }
            overrides.put(entry.fieldKey(), patch);
        }
        service.patch(draftId, request.hometaxReference(), overrides,
                request.confirmedFieldKeysOrEmpty());
        return ApiResponse.ok(query.detail(draftId));
    }

    /**
     * 내보내기 (14.3).
     *
     * <p><b>파일을 저장하지 않는다.</b> 요청이 올 때 만들어 응답으로만 돌려주고,
     * {@code sensitiveValues} 는 그 파일에만 존재한다 — DB 에도 로그에도 값이 남지 않는다.
     *
     * <p><b>인증이 헤더라 {@code <a href>} 로는 못 받는다.</b> 브라우저 기본 다운로드는
     * {@code Authorization} 을 싣지 않는다. 화면은 {@code fetch} → {@code blob} 으로 받는다.
     */
    @PostMapping("/tax-drafts/{draftId}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long draftId,
                                         @Valid @RequestBody ExportRequest request) {
        if (!"XLSX_HOMETAX_INPUT".equals(request.format())) {
            // PDF 는 별지 서식의 칸 배치를 재현하는 일이라 따로 잡는다. 조용히 XLSX 를
            // 내보내면 사장님은 PDF 를 받은 줄 알고 그것을 제출하려 한다.
            throw new BusinessException(ErrorCode.EXPORT_FORMAT_NOT_SUPPORTED,
                    "지금은 XLSX_HOMETAX_INPUT 만 내보낼 수 있습니다.");
        }

        byte[] body = renderer.render(assembler.assemble(
                draftId, request.includeBusinessIdsOrEmpty(), request.sensitiveValuesOrEmpty()));

        String fileName = "신고서초안_%d.xlsx".formatted(draftId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(fileName, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    /**
     * @param sensitiveValues 주소지·생년월일. <b>요청 본문에만 존재한다</b> — 저장하지 않고
     *                        로그에도 값을 남기지 않는다(필드 키와 시각만)
     */
    public record ExportRequest(@NotNull @Pattern(regexp = "^[A-Z_]{3,30}$") String format,
                                List<Long> includeBusinessIds,
                                Map<String, String> sensitiveValues) {

        List<Long> includeBusinessIdsOrEmpty() {
            return includeBusinessIds == null ? List.of() : includeBusinessIds;
        }

        Map<String, String> sensitiveValuesOrEmpty() {
            return sensitiveValues == null ? Map.of() : sensitiveValues;
        }
    }

    /**
     * @param overrides          항목별 수정값. <b>집계값과 분리 저장</b>되어 근거 추적이 남는다
     * @param confirmedFieldKeys 사장님이 확인했다고 표시한 항목
     */
    public record PatchRequest(Map<String, Object> hometaxReference,
                               @Valid List<OverrideEntry> overrides,
                               List<@Pattern(regexp = "^[A-Z_]{2,40}$") String> confirmedFieldKeys) {

        List<OverrideEntry> overridesOrEmpty() {
            return overrides == null ? List.of() : overrides;
        }

        List<String> confirmedFieldKeysOrEmpty() {
            return confirmedFieldKeys == null ? List.of() : confirmedFieldKeys;
        }
    }

    /** {@code value} 가 {@code null} 이면 「비움」이다 — 적었던 값을 지우는 경로다. */
    public record OverrideEntry(@NotNull @Pattern(regexp = "^[A-Z_]{2,40}$") String fieldKey,
                                BigDecimal value,
                                @Size(max = 500) String note) {
    }

    /**
     * 항목의 근거 거래 (14.3 {@code basis}).
     *
     * <p>사용자가 값을 넣는 항목(㉛ 감가상각비 등)은 <b>빈 목록</b>이다. 오류가 아니다 —
     * 근거로 삼을 거래가 애초에 없는 항목이다.
     */
    @GetMapping("/tax-drafts/{draftId}/basis")
    public ApiResponse<Map<String, Object>> basis(@PathVariable Long draftId,
                                                  @RequestParam String fieldKey,
                                                  @PageableDefault(size = 50) Pageable pageable) {
        return ApiResponse.ok(query.basis(draftId, fieldKey, pageable));
    }

    /**
     * @param hometaxReference 사용자가 홈택스 신고도움서비스에서 옮겨 적은 참고값. 선택이다 —
     *                         <b>서비스는 신고 유형·기장의무를 판정하지 않는다</b>(도메인 규칙 4).
     *                         표시와 수입금액 대사에만 쓴다
     */
    public record CreateRequest(@NotNull @Min(2020) @Max(2100) Integer taxYear,
                                @NotNull @Pattern(regexp = "^[A-Z_]{2,30}$") String formType,
                                @NotNull LocalDate periodStart,
                                @NotNull LocalDate periodEnd,
                                Map<String, Object> hometaxReference) {
    }

    /** {@code 202}. 집계가 끝나면 {@code status} 가 {@code READY} 가 된다. */
    public record CreatedResponse(Long draftId, String status, Long jobId) {
    }
}
