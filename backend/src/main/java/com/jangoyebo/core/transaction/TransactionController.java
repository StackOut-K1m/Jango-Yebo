package com.jangoyebo.core.transaction;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.response.PageResponse;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import com.jangoyebo.core.category.TransactionCategoryRepository;
import com.jangoyebo.core.job.JobResponse;
import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.job.JobType;
import com.jangoyebo.core.transaction.TransactionDtos.CurrentClassificationJob;
import com.jangoyebo.core.transaction.TransactionDtos.TransactionResponse;
import com.jangoyebo.core.transaction.TransactionDtos.UpdateRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 거래 조회·수정·CSV 업로드 (API 명세 7.4, 8.2~8.3). */
@RestController
@RequestMapping("/api/v1")
public class TransactionController {

    /** {@code from}·{@code to} 생략 시 최근 31일 (API 8.2). */
    private static final int DEFAULT_RANGE_DAYS = 31;

    private final TransactionRepository transactionRepository;
    private final TransactionCategoryRepository categoryRepository;
    private final TransactionImportService importService;
    private final TransactionClassificationService classificationService;
    private final JobService jobService;
    private final TaxReviewService taxReviewService;
    private final BusinessGuard guard;

    public TransactionController(TransactionRepository transactionRepository,
                                 TransactionCategoryRepository categoryRepository,
                                 TransactionImportService importService,
                                 TransactionClassificationService classificationService,
                                 JobService jobService,
                                 TaxReviewService taxReviewService,
                                 BusinessGuard guard) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.importService = importService;
        this.classificationService = classificationService;
        this.jobService = jobService;
        this.taxReviewService = taxReviewService;
        this.guard = guard;
    }

    /**
     * 거래 목록 (API 8.2).
     *
     * <p><b>해제된 계좌의 거래는 기본으로 빠진다.</b> {@code includeDisconnected=true} 로만
     * 함께 나온다 — 왜 그렇게 갈랐는지는 {@link TransactionRepository#search} 에 적어 두었다.
     */
    @GetMapping("/businesses/{businessId}/transactions")
    @Transactional(readOnly = true)
    public ApiResponse<PageResponse<TransactionResponse>> list(
            @CurrentBusiness Long businessId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) FlowType direction,
            @RequestParam(required = false) ClassificationStatus classificationStatus,
            @RequestParam(required = false) Boolean needsConfirmation,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String taxReviewStatus,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean includeDisconnected,
            @PageableDefault(size = 20, sort = "transactionDate", direction = Sort.Direction.DESC)
            Pageable pageable) {

        LocalDate toDate = to != null ? to : LocalDate.now();
        LocalDate fromDate = from != null ? from : toDate.minusDays(DEFAULT_RANGE_DAYS);

        Page<Transaction> page = transactionRepository.search(
                businessId, fromDate, toDate, accountId, direction,
                classificationStatus, needsConfirmation,
                resolveCategoryId(category), minAmount, maxAmount,
                parseTaxReviewStatuses(taxReviewStatus),
                (q == null || q.isBlank()) ? null : q.trim(),
                includeDisconnected, pageable);

        Map<Long, String> categoryCodes = categoryRepository.codeById();
        return ApiResponse.ok(PageResponse.from(page,
                t -> TransactionResponse.of(t, categoryCodes.get(t.getCategoryId()))));
    }

    @GetMapping("/transactions/{transactionId}")
    @Transactional(readOnly = true)
    public ApiResponse<TransactionResponse> get(@PathVariable Long transactionId) {
        Transaction tx = find(transactionId);
        return ApiResponse.ok(TransactionResponse.of(tx, categoryRepository.codeById().get(tx.getCategoryId())));
    }

    /** 사용자 확정. 카테고리를 바꾸면 {@code CONFIRMED}·{@code source = USER} 가 된다(API 8.3). */
    @PatchMapping("/transactions/{transactionId}")
    @Transactional
    public ApiResponse<TransactionResponse> update(@PathVariable Long transactionId,
                                                   @Valid @RequestBody UpdateRequest request) {
        Transaction tx = find(transactionId);

        Long categoryId = null;
        if (request.category() != null) {
            categoryId = categoryRepository.findByCodeAndActiveTrue(request.category())
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "알 수 없는 카테고리입니다: " + request.category()))
                    .getId();
        }
        // 분류를 바꿨으면 이력이 남는다 — 그것이 다음 LLM 분류의 few-shot 원천이다(8.3).
        classificationService.confirmByUser(tx, categoryId, request.memo(), request.isExcluded(),
                guard.currentUserId());
        return ApiResponse.ok(TransactionResponse.of(tx, categoryRepository.codeById().get(tx.getCategoryId())));
    }

    /**
     * AI 제안 수락 (API 8.4).
     *
     * <p>확인 질문에서 「맞아요」를 눌렀을 때다. 본문이 없다 — 수락은 <b>제안된 분류를 그대로</b>
     * 확정하는 것이고, 다른 분류를 고르는 것은 8.3 PATCH 다.
     *
     * <p><b>제안 상태가 아니면 409 다.</b> 이미 확정된 거래에 수락이 오면 사용자가 보고 있던
     * 화면이 낡은 것이다 — 조용히 성공으로 처리하면 그 사이 누가 바꿔 놓은 분류를 확정한 것이
     * 되고, 화면은 무엇이 확정됐는지 모른 채 성공만 받는다.
     *
     * <p>수락도 이력을 남긴다. 사람이 옳다고 한 짝이라 예시로서의 가치가 제안과 다르다 —
     * 이유는 {@link TransactionClassificationService#confirmByUser} 에 적었다.
     */
    @PostMapping("/transactions/{transactionId}/confirm-classification")
    @Transactional
    public ApiResponse<TransactionResponse> confirmClassification(@PathVariable Long transactionId) {
        Transaction tx = find(transactionId);
        // 분류가 비어 있는 SUGGESTED 는 있을 수 없지만, 그때 수락하면 아무것도 안 하고 200 이
        // 나간다 — 화면은 확정됐다고 읽는다.
        if (tx.getClassificationStatus() != ClassificationStatus.SUGGESTED
                || tx.getCategoryId() == null) {
            throw new BusinessException(ErrorCode.CLASSIFICATION_STATE_CONFLICT);
        }
        classificationService.confirmByUser(tx, tx.getCategoryId(), null, null,
                guard.currentUserId());
        return ApiResponse.ok(
                TransactionResponse.of(tx, categoryRepository.codeById().get(tx.getCategoryId())));
    }

    /**
     * LLM 분류 수동 재실행 (API 8.5).
     *
     * <p><b>자동 실행이 놓친 것을 회수하는 문이다.</b> 분류는 동기화·CSV 임포트가 끝나면
     * 스스로 돌지만, 그 순간을 놓친 거래가 남는 구간이 있다 — 워커가 죽어 있던 동안,
     * 이어달리기가 진전 없이 멈춘 뒤, 계좌를 되살린 뒤(연결 해제는 거래를 분류 대상에서
     * 빼고 재연결은 이벤트를 발행하지 않는다). 그때 사용자가 누르는 버튼이 이것이다.
     *
     * <p><b>무엇을 분류할지는 받지 않는다.</b> 명세 초안에는 {@code transactionIds} 가 있었으나
     * 합의 ④ 이후 대상은 <b>워커에 보낼 때 다시 조회</b>해서 정한다. 목록을 요청이 지목하면
     * 그것은 버튼을 누른 순간의 사진이라, 왕복 5분 사이에 사용자가 직접 분류했거나 계좌가
     * 해제된 거래까지 값을 치르고 보내게 된다.
     *
     * <p>이미 도는 작업이 있으면 <b>그것을 그대로 돌려준다</b> — 이유는
     * {@link com.jangoyebo.core.job.JobService#enqueueOrCurrent} 에 적었다.
     */
    @PostMapping("/businesses/{businessId}/classification-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<JobResponse> requestClassification(@CurrentBusiness Long businessId) {
        return ApiResponse.ok(JobResponse.from(jobService.enqueueOrCurrent(
                businessId, guard.currentUserId(), JobType.TRANSACTION_CLASSIFICATION, Map.of())));
    }

    /**
     * 진행 중인 분류 작업 조회 (API 8.5.1).
     *
     * <p><b>화면이 돌아왔을 때를 위한 문이다.</b> 분류는 브라우저와 무관하게 서버에서 도는데
     * (워커 스레드 + 콜백 + 이어달리기), 진행 표시는 화면의 로컬 상태라 다른 페이지로 나갔다
     * 오면 사라진다. 그러면 사용자는 <b>돌고 있는 줄 모르고</b> 버튼을 다시 누른다.
     *
     * <p><b>{@code POST} 로 대신할 수 없다.</b> 8.5 는 도는 것이 있으면 그것을 돌려주지만
     * 없으면 <b>새로 만든다</b> — 화면에 들어올 때마다 분류가 시작된다.
     *
     * <p>도는 것이 없어도 {@code 200} 이다. 이유는
     * {@link TransactionDtos.CurrentClassificationJob} 에 적었다.
     */
    @GetMapping("/businesses/{businessId}/classification-jobs/current")
    public ApiResponse<CurrentClassificationJob> currentClassificationJob(@CurrentBusiness Long businessId) {
        return ApiResponse.ok(CurrentClassificationJob.of(
                jobService.findUnfinished(businessId, JobType.TRANSACTION_CLASSIFICATION).orElse(null)));
    }


    /**
     * 종합소득세 검토 상태 일괄 변경 (8.7).
     *
     * <p>내보내기 {@code 07_거래증빙_연결목록.xlsx} 의 「검토 상태」 열이 이 값에서 나온다.
     * 검토 화면(SC-07-7)의 기본 저장 경로라 일괄이 먼저다 — 96건을 한 건씩 누르게 하면
     * 그 화면은 쓰이지 않는다.
     *
     * <p><b>남의 사업장 거래가 섞여 있어도 통째로 거절하지 않는다.</b> 되는 것은 반영하고
     * 안 되는 것만 {@code failed} 로 돌려준다.
     *
     * <p>증빙 확인 여부({@code EVIDENCE_CONFIRMED}·{@code EVIDENCE_MISSING})는 여기로 못
     * 보낸다 — 증빙을 연결하면 자동으로 정해지는 값이다.
     */
    @PostMapping("/businesses/{businessId}/tax-review/bulk")
    public ApiResponse<TaxReviewService.Result> bulkTaxReview(
            @CurrentBusiness Long businessId,
            @RequestBody TaxReviewBulkRequest request) {
        return ApiResponse.ok(taxReviewService.bulkUpdate(businessId,
                request.transactionIds(), parseTaxReviewStatus(request.taxReviewStatus()),
                request.taxReviewNote()));
    }

    /** 모르는 값은 거절한다 — 조용히 무시하면 안 바뀐 채로 저장됐다고 보인다. */
    private TaxReviewStatus parseTaxReviewStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "검토 상태를 골라 주세요.");
        }
        try {
            return TaxReviewStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "알 수 없는 검토 상태입니다: " + raw);
        }
    }

    /** 검토 상태 일괄 변경 요청 (8.7). */
    public record TaxReviewBulkRequest(List<Long> transactionIds,
                                       String taxReviewStatus,
                                       String taxReviewNote) {
    }

    @PostMapping(value = "/accounts/{accountId}/transactions/import",
            consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<JobResponse> importCsv(@PathVariable Long accountId,
                                              @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(JobResponse.from(importService.importCsv(accountId, file)));
    }

    private Transaction find(Long transactionId) {
        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND));
        // 직접 경로다 — Hibernate 필터가 걸리지 않으므로 명시 검증이 필요하다.
        guard.assertOwned(tx);
        return tx;
    }

    /**
     * 카테고리 코드를 id 로 바꾼다. 쿼리는 {@code category_id} 로 걸어야 인덱스를 탄다.
     *
     * <p><b>모르는 코드는 400 이다.</b> 조용히 무시하면 필터가 안 걸린 전체 목록이 나가고,
     * 프론트는 그것을 "해당 카테고리 거래가 이만큼"으로 읽는다 — 오타 한 글자가 잘못된
     * 집계로 보이는 편이 빈 목록보다 나쁘다.
     */
    private Long resolveCategoryId(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return categoryRepository.findByCodeAndActiveTrue(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "알 수 없는 카테고리입니다: " + code))
                .getId();
    }

    /**
     * {@code taxReviewStatus} 는 쉼표로 복수 지정할 수 있다(API 8.2).
     *
     * <p>검토 화면(P1-23)이 "확인이 필요한 것만" 같은 묶음을 한 번에 거르기 때문이다.
     * 여기서도 모르는 값은 400 이다 — 이유는 {@link #resolveCategoryId} 와 같다.
     *
     * <p>빈 집합이 아니라 {@code null} 을 돌려준다. JPQL 이 {@code IS NULL} 로 조건 자체를
     * 건너뛰는데, 빈 컬렉션을 넘기면 {@code IN ()} 이 되어 <b>아무것도 안 나온다.</b>
     */
    private Set<TaxReviewStatus> parseTaxReviewStatuses(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Set<TaxReviewStatus> statuses = EnumSet.noneOf(TaxReviewStatus.class);
        for (String token : raw.split(",")) {
            String value = token.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                statuses.add(TaxReviewStatus.valueOf(value));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "알 수 없는 검토 상태입니다: " + value);
            }
        }
        return statuses.isEmpty() ? null : statuses;
    }
}
