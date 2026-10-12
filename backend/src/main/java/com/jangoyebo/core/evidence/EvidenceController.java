package com.jangoyebo.core.evidence;

import com.jangoyebo.core.job.Job;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import com.jangoyebo.common.response.PageResponse;
import com.jangoyebo.core.evidence.EvidenceDtos.EvidenceDetail;
import com.jangoyebo.core.evidence.EvidenceDtos.EvidenceSummary;
import com.jangoyebo.core.evidence.EvidenceDtos.ExtractionPatch;
import com.jangoyebo.core.evidence.EvidenceService.Uploaded;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

/** 증빙 (API 13장, P0-10). */
@RestController
@RequestMapping("/api/v1")
public class EvidenceController {

    private final EvidenceService evidenceService;
    private final EvidenceReviewService reviewService;
    private final EvidenceMatchService matchService;
    private final EvidenceFileService fileService;

    public EvidenceController(EvidenceService evidenceService,
                              EvidenceReviewService reviewService,
                              EvidenceMatchService matchService,
                              EvidenceFileService fileService) {
        this.evidenceService = evidenceService;
        this.reviewService = reviewService;
        this.matchService = matchService;
        this.fileService = fileService;
    }

    /**
     * 원본 미리보기 (13.1 {@code fileUrl}).
     *
     * <p><b>다운로드가 아니라 미리보기다.</b> {@code inline} 이라 브라우저가 이미지·PDF 를 그대로
     * 그린다 — 검토 화면이 추출값 옆에 원본을 띄우는 것이 이 경로의 목적이다.
     *
     * <p><b>{@code <img src>} 로는 못 받는다.</b> 인증이 헤더라 브라우저 기본 요청에는
     * {@code Authorization} 이 실리지 않는다. 화면은 {@code fetch} → {@code blob} → object URL
     * 로 받는다(14.2·14.3 내보내기와 같은 사정).
     *
     * <p>지운 증빙은 {@code 404 EVIDENCE_NOT_FOUND}, 기록은 있는데 파일이 없으면
     * {@code 404 EVIDENCE_FILE_MISSING} 이다 — 사장님이 할 수 있는 일이 다르다.
     */
    @GetMapping("/evidences/{evidenceId}/file")
    public ResponseEntity<Resource> file(@PathVariable Long evidenceId) {
        EvidenceFileService.StoredFile stored = fileService.locate(evidenceId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(stored.fileName(), StandardCharsets.UTF_8)
                                .build().toString())
                .contentLength(stored.size())
                .contentType(MediaType.parseMediaType(stored.mimeType()))
                .body(new InputStreamResource(fileService.open(stored.storageKey())));
    }


    /**
     * 증빙 업로드 (13.1). {@code 202} — 파일을 받고 추출 Job 을 건 상태다.
     *
     * <p>{@code evidenceType}·{@code transactionId} 는 선택이다. 종류를 안 주면 추출이 추정하고,
     * 거래를 안 주면 나중에 붙인다 — <b>평소에 축적하는 것이 이 기능의 목적</b>이라 연결을
     * 강제하지 않는다(SRS P0-10).
     */
    @PostMapping(value = "/businesses/{businessId}/evidences", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<EvidenceUploadResponse> upload(
            @CurrentBusiness Long businessId,
            @RequestPart("file") MultipartFile file,
            // 파일만 파트고 나머지는 폼 필드다. @RequestPart 로 받으면 Content-Type 이 붙은
            // 파트만 찾아서 일반 텍스트 필드가 안 들어온다.
            @RequestParam(value = "evidenceType", required = false) String evidenceType,
            @RequestParam(value = "transactionId", required = false) String transactionId) {

        Uploaded uploaded = evidenceService.upload(businessId, file,
                parseType(evidenceType), parseId(transactionId));
        return ApiResponse.ok(EvidenceUploadResponse.of(uploaded));
    }


    /**
     * 재추출 (13.7). {@code 202} — 같은 증빙을 {@code THOROUGH} 로 다시 읽는 Job 을 건 상태다.
     *
     * <p>검토 화면의 「다시 읽기」가 부른다. 추출이 끝난({@code EXTRACTED}) 증빙만 되고,
     * 확정된 것은 {@code 409 EVIDENCE_STATE_CONFLICT} 다 — 확정본을 조용히 내리지 않는다.
     *
     * <p>결과는 콜백으로 온다. 화면은 업로드 때와 같은 방식으로 상태를 다시 조회한다.
     */
    @PostMapping("/evidences/{evidenceId}/re-extract")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ReExtractResponse> reExtract(@PathVariable Long evidenceId) {
        Job job = evidenceService.reExtract(evidenceId);
        return ApiResponse.ok(new ReExtractResponse(evidenceId, job.getId(), "THOROUGH"));
    }

    /** 재추출 접수 응답 (13.7). 화면은 {@code ocrJobId} 로 진행을 따라간다. */
    public record ReExtractResponse(Long evidenceId, Long ocrJobId, String extractionMode) {
    }

    /** 힌트일 뿐이라 모르는 값이면 거절하지 않고 비운다 — 추출이 어차피 추정한다. */
    private EvidenceType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EvidenceType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "알 수 없는 증빙 종류입니다: " + raw);
        }
    }

    /** multipart 파트는 문자열로 온다. 빈 값·공백은 "안 준 것"으로 본다. */
    private Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "transactionId 가 숫자가 아닙니다.");
        }
    }

    /**
     * 증빙 목록 (13.2).
     *
     * <p>{@code linked=false} 가 이 화면의 핵심 필터다 — "거래에 안 붙은 증빙"을 좁히는 것이
     * P0-10 의 목적이다. 기간은 <b>업로드 시각</b> 기준이고, 발행일은 추출 후에야 채워지므로
     * 그것으로 걸면 갓 올린 증빙이 사라진다.
     *
     * <p>정렬 기본값은 업로드 역순이다. <b>검토 화면은 {@code sort=needsReview,desc} 를 앞에
     * 붙여 "먼저 볼 곳"을 위로 올린다</b>(P1-02 완료 기준) — {@code needsReview} 가
     * {@code Evidence} 의 컬럼이라 {@link Pageable} 이 그대로 받는다.
     *
     * <pre>{@code
     * ?ocrStatus=EXTRACTED&sort=needsReview,desc&sort=createdAt,desc
     * }</pre>
     *
     * <p>{@code needsReview} 를 <b>필터</b>로도 쓸 수 있다(확인 필요한 것만). 값이 {@code null} 인
     * 증빙은 <b>아직 추출 결과가 없는 것</b>이라 어느 쪽 필터에도 걸리지 않는다 —
     * {@code false}(검산 통과)와 다르다.
     */
    @GetMapping("/businesses/{businessId}/evidences")
    public ApiResponse<PageResponse<EvidenceSummary>> list(
            @CurrentBusiness Long businessId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String ocrStatus,
            @RequestParam(required = false) String evidenceType,
            @RequestParam(required = false) Boolean linked,
            @RequestParam(required = false) Boolean needsReview,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        return ApiResponse.ok(PageResponse.from(reviewService.list(
                businessId, from, to, parseStatus(ocrStatus), parseType(evidenceType),
                linked, needsReview, pageable)));
    }

    /** 증빙 상세 (13.2). 추출 전이거나 실패했으면 {@code extraction} 키가 빠진다. */
    @GetMapping("/evidences/{evidenceId}")
    public ApiResponse<EvidenceDetail> get(@PathVariable Long evidenceId) {
        return ApiResponse.ok(reviewService.get(evidenceId));
    }

    /**
     * 추출 결과 수정·확정 (13.3).
     *
     * <p><b>보낸 필드만 바뀐다.</b> {@code confirmed: true} 면 {@code ocrStatus = CONFIRMED} 가
     * 되고, 바뀐 값은 {@code EVIDENCE_CORRECTION} 에 필드별로 남는다 — <b>고친 것이 없어도
     * 확인 시각은 기록된다</b>(합의 ⑦. 그 분모가 없으면 실사용 정확도를 낼 수 없다).
     *
     * <p>{@code ocrStatus} 가 {@code EXTRACTED}·{@code CONFIRMED} 가 아니면 409 다.
     */
    @PatchMapping("/evidences/{evidenceId}/extraction")
    public ApiResponse<EvidenceDetail> patchExtraction(@PathVariable Long evidenceId,
                                                       @RequestBody ExtractionPatch patch) {
        return ApiResponse.ok(reviewService.patchExtraction(evidenceId, patch));
    }


    // ------------------------------------------------------------------ 13.4

    /**
     * 매칭 후보 (13.4). 점수순으로 내려간다.
     *
     * <p>발행일을 아직 모르면 <b>빈 목록</b>이다 — 오류가 아니다. 어디를 기준으로 찾을지가
     * 정해지지 않았을 뿐이고, 추출이 끝나면 채워진다.
     */
    @GetMapping("/evidences/{evidenceId}/match-candidates")
    public ApiResponse<CandidateList> matchCandidates(@PathVariable Long evidenceId) {
        return ApiResponse.ok(new CandidateList(matchService.candidates(evidenceId)));
    }

    /**
     * 거래 연결 (13.4).
     *
     * <p>이미 다른 거래에 붙어 있으면 {@code 409 EVIDENCE_ALREADY_LINKED} 다. 같은 거래에
     * 다시 붙이는 것은 성공으로 둔다 — 화면이 두 번 눌렀을 뿐인데 실패로 보일 이유가 없다.
     */
    @PostMapping("/evidences/{evidenceId}/link")
    public ApiResponse<Void> link(@PathVariable Long evidenceId,
                                  @RequestBody LinkRequest request) {
        matchService.link(evidenceId, request.transactionId());
        return ApiResponse.ok(null);
    }

    /** 연결 해제 (13.4). 붙어 있지 않아도 {@code 204} 다. */
    @DeleteMapping("/evidences/{evidenceId}/link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@PathVariable Long evidenceId) {
        matchService.unlink(evidenceId);
    }

    // ------------------------------------------------------------------ 13.5

    /**
     * 증빙 메타 수정 (13.5). 지금은 {@code evidenceType} 하나다.
     *
     * <p>추출이 종류를 잘못 추정했을 때 사람이 고치는 자리다. 13.3 과 달리 <b>추출 결과가
     * 아니라 증빙 자체</b>를 고치는 것이라 수정 이력({@code EVIDENCE_CORRECTION})에 남기지
     * 않는다 — 그쪽은 모델이 읽은 값의 정확도를 재는 표다.
     */
    @PatchMapping("/evidences/{evidenceId}")
    public ApiResponse<EvidenceDetail> patchMeta(@PathVariable Long evidenceId,
                                                 @RequestBody MetaPatch patch) {
        evidenceService.changeType(evidenceId, parseType(patch.evidenceType()));
        return ApiResponse.ok(reviewService.get(evidenceId));
    }


    /**
     * 증빙 삭제 (13.5). {@code 204} — <b>소프트 삭제다.</b>
     *
     * <p>목록·상세에서 사라지고 거래 연결도 끊긴다. 파일과 추출·수정 이력은 남는다 —
     * 수정 이력이 실사용 정확도의 원천이고, 파일은 내보낸 신고 자료의 근거다.
     *
     * <p>이미 지워진 것을 또 지워도 {@code 204} 다.
     */
    @DeleteMapping("/evidences/{evidenceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long evidenceId) {
        evidenceService.softDelete(evidenceId);
    }

    /** 매칭 후보 목록 (13.4). */
    public record CandidateList(List<EvidenceMatchService.Candidate> items) {
    }

    /** 거래 연결 요청 (13.4). */
    public record LinkRequest(Long transactionId) {
    }

    /** 증빙 메타 수정 (13.5). */
    public record MetaPatch(String evidenceType) {
    }

    /** 목록 필터라 모르는 값은 거절한다 — 조용히 무시하면 안 걸린 전체 목록이 나간다. */
    private EvidenceStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EvidenceStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "알 수 없는 추출 상태입니다: " + raw);
        }
    }

    /** 명세 13.1 응답. */
    public record EvidenceUploadResponse(Long evidenceId,
                                         String evidenceType,
                                         String fileName,
                                         String fileUrl,
                                         String ocrStatus,
                                         Long ocrJobId,
                                         Long linkedTransactionId,
                                         Instant uploadedAt) {

        static EvidenceUploadResponse of(Uploaded uploaded) {
            Evidence evidence = uploaded.evidence();
            return new EvidenceUploadResponse(
                    evidence.getId(),
                    evidence.getEvidenceType().name(),
                    evidence.getOriginalFilename(),
                    EvidenceDtos.fileUrlOf(evidence.getId()),
                    evidence.getProcessingStatus().name(),
                    uploaded.job().getId(),
                    uploaded.linkedTransactionId(),
                    evidence.getCreatedAt());
        }
    }
}
