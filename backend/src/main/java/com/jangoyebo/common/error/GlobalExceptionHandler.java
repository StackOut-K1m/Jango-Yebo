package com.jangoyebo.common.error;

import com.jangoyebo.common.response.ApiResponse;
import org.hibernate.query.sqm.UnknownPathException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 전역 예외 → 공통 오류 Envelope 변환 (API 명세 2.4·22장).
 *
 * <p>HTTP 상태는 {@link ErrorCode}가 들고 있으므로 여기서 따로 정하지 않는다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        // 업무 예외는 예상된 흐름이다. 스택트레이스를 남기지 않는다.
        log.debug("business error: {} - {}", e.errorCode(), e.getMessage());
        return toResponse(e.errorCode(), e.getMessage(), e.detail());
    }

    /** {@code @Valid} 실패. 어느 필드가 왜 틀렸는지 detail 에 담아 프론트가 표시할 수 있게 한다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> fields = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage(),
                        (a, b) -> a,
                        LinkedHashMap::new));
        return toResponse(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), fields);
    }

    /**
     * 본문을 읽을 수 없는 요청 — 깨진 JSON, 필드 타입 불일치, 본문 누락.
     *
     * <p>클라이언트 잘못이므로 400 이다. 이 핸들러가 없으면 {@code Exception} 폴백으로 떨어져
     * <b>500 이 나가고</b>, 프론트는 자기 요청이 틀린 것을 서버 장애로 오인한다.
     *
     * <p>파서 예외 메시지에는 입력 바이트·내부 경로가 섞이므로 응답에 그대로 싣지 않고 로그로만 남긴다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.debug("unreadable request body: {}", e.getMostSpecificCause().getMessage());
        return toResponse(ErrorCode.VALIDATION_FAILED, "요청 본문을 읽을 수 없습니다.", null);
    }

    /**
     * 경로·쿼리 변수의 타입 불일치 — {@code /transactions/abc} 처럼 BIGINT 자리에 문자열이 온 경우.
     *
     * <p>위와 같은 이유로 400 이다. ID 가 전부 {@code BIGINT} 라(ERD §11) 프론트 링크가 잘못되면
     * 바로 걸리는 경로이므로 500 으로 두면 장애 조사에 시간을 쓰게 된다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.debug("type mismatch: {} = {}", e.getName(), e.getValue());
        return toResponse(ErrorCode.VALIDATION_FAILED,
                "'%s' 값의 형식이 올바르지 않습니다.".formatted(e.getName()), null);
    }

    /**
     * 존재하지 않는 정렬 필드 — {@code ?sort=bogus,asc}.
     *
     * <p>{@code sort} 는 쿼리 문자열에서 오는 <b>요청값</b>이므로 위 두 건과 같은 이유로 400 이다.
     * {@code @Query} + {@code Sort} 조합은 Hibernate 가 {@link UnknownPathException} 을,
     * 파생 쿼리·Specification 은 Spring Data 가 {@link PropertyReferenceException} 을 던진다.
     *
     * <p>전자는 {@code InvalidDataAccessApiUsageException} 에 싸여 온다. Spring MVC 는 직접
     * 맞는 핸들러가 없을 때만 원인 체인을 따라가는데, 아래 {@code Exception} 폴백이 래퍼를
     * <b>먼저 잡아버리므로</b> 래퍼 타입을 여기서 직접 받아 원인을 들여다봐야 한다.
     * 정렬 문제가 아닌 {@code InvalidDataAccessApiUsageException} 은 서버 잘못이므로 500 이다.
     *
     * <p>정렬 가능 필드 목록은 명세가 정해 두지 않았다(공통규약 2.3 은 예시만 든다). 그래서
     * 화이트리스트를 새로 만들지 않고 <b>엔티티 속성이면 통과</b>시킨다 — {@code sort=amount,desc}
     * 같은 요청을 막지 않기 위해서다.
     */
    @ExceptionHandler({PropertyReferenceException.class, InvalidDataAccessApiUsageException.class})
    public ResponseEntity<ApiResponse<Void>> handleQueryUsage(Exception e) {
        if (!isUnknownProperty(e)) {
            return handleUnexpected(e);
        }
        log.debug("unknown sort property: {}", e.getMessage());
        return toResponse(ErrorCode.VALIDATION_FAILED, "정렬할 수 없는 필드입니다.", null);
    }

    private static boolean isUnknownProperty(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownPathException || t instanceof PropertyReferenceException) {
                return true;
            }
        }
        return false;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadSize(MaxUploadSizeExceededException e) {
        // nginx client_max_body_size(32m) 가 Spring 설정보다 커서 여기까지 도달한다.
        return toResponse(ErrorCode.EVIDENCE_FILE_UNSUPPORTED, "업로드 가능한 크기를 초과했습니다.", null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return toResponse(ErrorCode.BUSINESS_ACCESS_DENIED, ErrorCode.BUSINESS_ACCESS_DENIED.defaultMessage(), null);
    }

    /** 존재하지 않는 경로. 기본 흰 오류 페이지 대신 Envelope 으로 통일한다. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return toResponse(ErrorCode.VALIDATION_FAILED, "요청 경로를 찾을 수 없습니다.", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        // 예상 못 한 오류만 스택트레이스를 남긴다.
        log.error("unhandled exception", e);
        return toResponse(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), null);
    }

    private ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode code, String message, Map<String, Object> detail) {
        return ResponseEntity.status(code.status()).body(ApiResponse.error(code, message, detail));
    }
}
