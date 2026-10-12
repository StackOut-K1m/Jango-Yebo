package com.jangoyebo.core.export;

import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.storage.UploadStorage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 신고 준비자료 내보내기 (API 14.2 · P0-12 부속 산출물).
 *
 * <p>세무대리인에게 통째로 넘길 ZIP 을 만든다. 홈택스 직접 신고용 주 산출물은 14.3 신고서
 * 초안이고 이쪽은 그 판단의 <b>근거 자료</b>다.
 *
 * <p>생성은 {@code EXPORT_GENERATION} Job 으로 비동기 처리한다 — 한 해 거래 전체를 훑고
 * 증빙 원본까지 담으므로 요청-응답 안에서 끝낼 수 없다.
 */
@RestController
@RequestMapping("/api/v1")
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);

    private final ExportService service;
    private final UploadStorage storage;
    private final BusinessGuard guard;

    public ExportController(ExportService service, UploadStorage storage, BusinessGuard guard) {
        this.service = service;
        this.storage = storage;
        this.guard = guard;
    }

    @PostMapping("/businesses/{businessId}/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Map<String, Object>> create(@CurrentBusiness Long businessId,
                                                   @Valid @RequestBody CreateRequest request) {
        Job job = service.request(businessId, guard.currentUserId(), request.exportType(),
                request.periodStart(), request.periodEnd(), request.includeEvidenceFilesOrFalse());
        return ApiResponse.ok(Map.of(
                "exportId", job.getId(),
                "exportType", request.exportType().name(),
                "status", "PROCESSING",
                "jobId", job.getId()));
    }

    /**
     * 내보내기 이력 (SC-07-6).
     *
     * <p><b>명세 14.2 에 없는 경로다</b>(구현현황 4장). 화면이 「상태·기간·생성일·다운로드」
     * 목록을 요구하는데 {@code GET /jobs/{jobId}} 는 단건뿐이라 이력을 만들 수 없었다.
     */
    @GetMapping("/businesses/{businessId}/exports")
    public ApiResponse<Map<String, List<Map<String, Object>>>> list(
            @CurrentBusiness Long businessId) {
        return ApiResponse.ok(Map.of("items", service.list(businessId)));
    }

    @GetMapping("/exports/{exportId}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long exportId) {
        return ApiResponse.ok(service.detail(exportId));
    }

    /**
     * 내려받기 (14.2).
     *
     * <p><b>모든 다운로드를 이력으로 남긴다</b>(비기능 요구 — 보안). 전용 테이블 대신 구조화
     * 로그에 적는다. 계좌 삭제({@code AccountPurgeService})가 같은 방식이고, 명세에 이력
     * 조회 화면이 없어 테이블을 두면 마이그레이션과 정리 정책만 늘어난다.
     *
     * <p><b>파일을 메모리에 올리지 않는다.</b> 증빙 원본이 들어가면 수백 MB 가 되므로
     * 스트림으로 흘려보낸다.
     *
     * <p><b>인증이 헤더라 {@code <a href>} 로는 못 받는다.</b> 브라우저 기본 다운로드는
     * {@code Authorization} 을 싣지 않는다 — 화면은 {@code fetch} → {@code blob} 으로 받는다.
     */
    @GetMapping("/exports/{exportId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long exportId) {
        ExportService.Downloadable file = service.downloadable(exportId);
        log.info("신고 준비자료 다운로드 — 내보내기 {} · 사용자 {} · 파일 {}",
                exportId, guard.currentUserId(), file.fileName());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.fileName(), StandardCharsets.UTF_8)
                                .build().toString())
                .contentLength(storage.sizeOf(file.storageKey()))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(storage.openRead(file.storageKey())));
    }

    /**
     * @param includeEvidenceFiles 증빙 원본을 패키지에 넣을지. 안 주면 넣지 않는다 — 켜면
     *                             ZIP 이 수백 MB 가 되므로 기본값을 「넣음」으로 두지 않는다
     */
    public record CreateRequest(@NotNull ExportType exportType,
                                @NotNull LocalDate periodStart,
                                @NotNull LocalDate periodEnd,
                                Boolean includeEvidenceFiles) {

        boolean includeEvidenceFilesOrFalse() {
            return Boolean.TRUE.equals(includeEvidenceFiles);
        }
    }
}
