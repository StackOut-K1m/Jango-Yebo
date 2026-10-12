package com.jangoyebo.core.support;

import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.core.support.SupportProgramDtos.BatchRequest;
import com.jangoyebo.core.support.SupportProgramDtos.BatchResponse;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공고 원문 배치 적재 (명세 20.8).
 *
 * <p>경로가 {@code /api/v1} 하위가 <b>아니다.</b> 수집 배치가 서비스 토큰으로 붙는 내부
 * 경로이며 {@code InternalSecurityConfig} 가 지킨다 — 사용자 JWT 로는 닿지 않는다.
 *
 * <p>⚠ 그 설정 주석이 경고하듯 nginx 가 {@code /internal/**} 을 막아야 한다. 안 막으면
 * 이 경로가 인터넷에서 직접 닿고 서비스 토큰이 유일한 방어선이 된다.
 */
@RestController
public class SupportProgramIngestController {

    private final SupportProgramIngestService ingestService;

    public SupportProgramIngestController(SupportProgramIngestService ingestService) {
        this.ingestService = ingestService;
    }

    /**
     * 경로에 콜론이 들어간다 — 명세 20.8 이 {@code :batch} 로 정했다(시세 적재도 같은 꼴).
     * 컬렉션에 대한 동작이라 하위 리소스처럼 보이는 {@code /batch} 와 구분한다.
     *
     * <p><b>클래스에 {@code @RequestMapping} prefix 를 두지 않는다.</b> 두면 Spring 이 prefix
     * 와 메서드 경로 사이에 슬래시를 끼워 {@code /support-programs/:batch} 로 등록한다 —
     * 명세의 경로와 한 글자 다르고, 수집기는 404 대신 <b>400 "요청 경로를 찾을 수 없습니다"</b>
     * 를 받는다(정적 리소스 핸들러까지 흘러가서다). 전체 경로를 한 곳에 적어 막는다.
     */
    @PostMapping("/internal/v1/support-programs:batch")
    public ApiResponse<BatchResponse> ingest(@Valid @RequestBody BatchRequest request) {
        return ApiResponse.ok(ingestService.ingest(request));
    }
}
