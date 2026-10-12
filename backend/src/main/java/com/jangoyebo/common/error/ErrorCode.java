package com.jangoyebo.common.error;

import org.springframework.http.HttpStatus;

/**
 * 오류 코드 (API 명세 22장).
 *
 * <p>명세의 코드 문자열이 곧 enum 이름이다. 응답의 {@code error.code}는 {@link #name()}을 쓰므로
 * <b>이름을 바꾸면 프론트 계약이 깨진다.</b> HTTP 상태를 함께 들고 있어 매핑이 한 곳에만 있다.
 *
 * <p>새 코드를 추가할 때는 API 22장 표에도 같이 넣는다.
 */
public enum ErrorCode {

    // --- 인증 ---
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해 주세요."),
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "인증 정보가 올바르지 않습니다."),
    AUTH_EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    AUTH_PASSWORD_POLICY_VIOLATION(HttpStatus.BAD_REQUEST, "비밀번호가 정책에 맞지 않습니다."),

    // --- 사업장 ---
    BUSINESS_NOT_FOUND(HttpStatus.NOT_FOUND, "사업장을 찾을 수 없습니다."),
    BUSINESS_ACCESS_DENIED(HttpStatus.FORBIDDEN, "해당 사업장에 접근할 수 없습니다."),

    // --- 계좌 ---
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "계좌를 찾을 수 없습니다."),
    ACCOUNT_ALREADY_LINKED(HttpStatus.CONFLICT, "이미 연결된 계좌입니다."),
    // 금융망 A1003 을 옮긴 것. 사용자가 계좌번호를 잘못 넣은 것이므로 502 가 아니다.
    // 문구가 뭉뚱그려진 것은 의도다 — 없는 계좌·형식 오류·남의 계좌가 구분되지 않으며,
    // 구분해 알려주면 계좌 존재 여부가 새어 나간다(BUSINESS_ACCESS_DENIED 와 같은 이유).
    ACCOUNT_NO_INVALID(HttpStatus.BAD_REQUEST, "계좌번호를 확인해 주세요."),
    ACCOUNT_NOT_VERIFIED(HttpStatus.CONFLICT, "1원 인증이 완료되지 않은 계좌입니다."),
    /** 그 계좌 상태에서 할 수 없는 동작. 지금은 해제된 계좌에 1원 재발송을 시도한 경우뿐이다. */
    ACCOUNT_STATE_CONFLICT(HttpStatus.CONFLICT, "계좌 상태에서 할 수 없는 동작입니다."),
    ACCOUNT_AUTH_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "인증코드가 일치하지 않습니다."),
    ACCOUNT_AUTH_EXPIRED(HttpStatus.CONFLICT, "인증 시간이 만료되었습니다. 다시 요청해 주세요."),

    // --- 외부 연동 ---
    FIN_API_FAILED(HttpStatus.BAD_GATEWAY, "금융망 연동에 실패했습니다."),
    CREDIT_FETCH_FAILED(HttpStatus.BAD_GATEWAY, "신용등급 조회에 실패했습니다."),
    AI_ANALYSIS_FAILED(HttpStatus.BAD_GATEWAY, "AI 분석에 실패했습니다."),
    EXTERNAL_API_FAILED(HttpStatus.BAD_GATEWAY, "외부 데이터 연동에 실패했습니다."),
    INTERNAL_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "서비스 간 인증에 실패했습니다."),

    // --- 거래 ---
    IMPORT_INVALID_FORMAT(HttpStatus.BAD_REQUEST, "CSV 형식이 올바르지 않습니다."),
    TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "거래를 찾을 수 없습니다."),
    CLASSIFICATION_STATE_CONFLICT(HttpStatus.CONFLICT, "분류 제안 상태가 아닌 거래입니다."),
    MERCHANT_NOT_FOUND(HttpStatus.NOT_FOUND, "거래처 사전 항목을 찾을 수 없습니다."),

    // --- 종합소득세 신고서 초안 (14.3) ---
    // 안내·체크리스트 전용 서식에 초안 생성을 요청한 경우. 목록에는 나오지만
    // 항목을 채우지 않는 유형이라, 조용히 빈 초안을 만들면 「집계가 0」으로 읽힌다.
    FORM_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "이 서식은 초안 생성을 지원하지 않습니다."),
    TAX_DRAFT_NOT_FOUND(HttpStatus.NOT_FOUND, "신고서 초안을 찾을 수 없습니다."),
    // 상한 초안(STALE)도 여기 걸린다. 근거가 달라진 숫자로 신고서를 내보내면 안 된다 —
    // 재생성이 사용자 수정값을 물려받으므로 버튼 한 번이면 된다.
    TAX_DRAFT_NOT_READY(HttpStatus.CONFLICT,
            "집계가 끝난 초안만 내보낼 수 있습니다. 재생성한 뒤 다시 시도해 주세요."),
    EXPORT_FORMAT_NOT_SUPPORTED(HttpStatus.BAD_REQUEST,
            "지원하지 않는 내보내기 형식입니다."),
    // 명세 22장에 없던 코드다(구현현황 4장). 집계 중인 초안을 지우면 핸들러가 「초안이 없다」로
    // 실패해 화면에는 실패한 작업만 남는다. 집계는 몇 초라 기다렸다 지우면 된다.
    // PLANNED_TX_NOT_DELETABLE 와 같은 모양이다.
    TAX_DRAFT_NOT_DELETABLE(HttpStatus.CONFLICT,
            "집계 중인 초안은 삭제할 수 없습니다. 잠시 뒤 다시 시도해 주세요."),
    // 민감 항목(주소지·생년월일)은 서버에 저장하지 않는다. 조용히 무시하면 화면은
    // 적힌 줄 알고 서버에는 없어, 내보내기에서 빈 칸으로 나간다.
    SENSITIVE_FIELD_NOT_STORABLE(HttpStatus.BAD_REQUEST,
            "민감 항목은 저장하지 않습니다. 내보내기 시점에 입력해 주세요."),

    // --- 반복 패턴 · 예정 거래 ---
    PATTERN_NOT_FOUND(HttpStatus.NOT_FOUND, "반복 패턴을 찾을 수 없습니다."),
    PATTERN_STATE_CONFLICT(HttpStatus.CONFLICT, "후보 상태가 아닌 패턴입니다."),
    PLANNED_TX_NOT_FOUND(HttpStatus.NOT_FOUND, "예정 거래를 찾을 수 없습니다."),
    PLANNED_TX_NOT_DELETABLE(HttpStatus.CONFLICT, "패턴이 생성한 예정 거래는 삭제할 수 없습니다."),
    MATCH_CONFLICT(HttpStatus.CONFLICT, "이미 다른 예정 거래와 매칭된 거래입니다."),

    // --- 예측 · 시나리오 ---
    FORECAST_INSUFFICIENT_DATA(HttpStatus.UNPROCESSABLE_CONTENT, "예측에 필요한 거래 데이터가 없습니다."),
    /**
     * 최신 예측 실행이 아직 없다. <b>오류가 아니라 「아직」이다.</b>
     *
     * <p>조회 API 는 계산하지 않으므로(공통규약 2.6) 실행이 없을 때 줄 것이 없다. 대신 이 응답을
     * 내면서 재계산을 예약하고, 화면은 잠시 뒤 다시 부른다. {@code EXPORT_NOT_READY} 와 같은
     * 모양이며 공통규약 22장 표에도 함께 넣었다.
     */
    FORECAST_NOT_READY(HttpStatus.CONFLICT, "예측 계산이 아직 완료되지 않았습니다."),
    SCENARIO_NOT_FOUND(HttpStatus.NOT_FOUND, "시나리오를 찾을 수 없습니다."),
    SCENARIO_PARAM_INVALID(HttpStatus.BAD_REQUEST, "지원하지 않는 시나리오 파라미터입니다."),
    /**
     * {@code PRODUCT_SALES_CHANGE} 를 구성비 등록이 없는 사업장에 건 경우 (API 11.1).
     *
     * <p><b>명세 본문에만 있고 22장 표에도 이 enum 에도 없던 코드다.</b> 시나리오 구현이
     * 없던 동안 이 코드를 낼 자리가 없어 드러나지 않았다. 11.1 이 이 문자열을 이미 약속했으므로
     * 이름을 새로 짓지 않고 그대로 쓴다 — 공통규약 22장 표에도 함께 넣었다.
     *
     * <p>{@code 404} 가 아닌 이유는 없는 리소스를 물은 것이 아니라 <b>선행 등록이 없어 지금은
     * 계산할 수 없다</b>는 상태이기 때문이다. 화면은 이 코드를 받아 상품별 매출 등록으로 안내한다.
     */
    PRODUCT_SALES_NOT_REGISTERED(HttpStatus.CONFLICT, "상품별 매출 구성비가 등록되지 않았습니다."),
    SAFETY_LINE_NOT_SET(HttpStatus.UNPROCESSABLE_CONTENT, "안전선이 설정되지 않았습니다."),
    RESERVE_NOT_FOUND(HttpStatus.NOT_FOUND, "준비금을 찾을 수 없습니다."),

    // --- 증빙 · 품목 ---
    EVIDENCE_NOT_FOUND(HttpStatus.NOT_FOUND, "증빙을 찾을 수 없습니다."),
    // 명세 22장에 없던 코드다(구현현황 4장). 기록은 있는데 저장소에 파일이 없는 경우를
    // EVIDENCE_NOT_FOUND 로 답하면 화면이 「증빙 자체가 없다」로 읽어 목록에서 지워야 할지
    // 판단하지 못한다. 사장님이 할 수 있는 일도 다르다 — 이쪽은 다시 올리는 것이다.
    EVIDENCE_FILE_MISSING(HttpStatus.NOT_FOUND, "증빙 원본 파일을 찾을 수 없습니다."),
    EVIDENCE_FILE_UNSUPPORTED(HttpStatus.BAD_REQUEST, "지원하지 않는 파일 형식 또는 크기입니다."),
    EVIDENCE_STATE_CONFLICT(HttpStatus.CONFLICT, "추출이 완료되지 않은 증빙입니다."),
    EVIDENCE_ALREADY_LINKED(HttpStatus.CONFLICT, "이미 다른 거래에 연결된 증빙입니다."),
    ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "품목을 찾을 수 없습니다."),

    // --- 정산 · 내보내기 ---
    SETTLEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "정산 대사를 찾을 수 없습니다."),
    // 명세 22장에 없던 코드다. 알림 확인 처리(10.3)에 404 가 필요해 추가하고
    // 공통규약 오류 코드 표에도 함께 넣었다(구현현황 4장).
    ALERT_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),
    EXPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "내보내기를 찾을 수 없습니다."),
    // 명세 22장에 없던 코드다(구현현황 4장). 브리핑 상세(15.2)에 404 가 필요한데 표에도
    // enum 에도 없었다 — ALERT_NOT_FOUND 와 같은 사정이다.
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "브리핑을 찾을 수 없습니다."),
    EXPORT_NOT_READY(HttpStatus.CONFLICT, "생성이 완료되지 않았습니다."),
    // 명세 22장에 없던 코드다(구현현황 4장). 14.2 가 다운로드 만료를 두는데 만료된 파일에
    // EXPORT_NOT_READY 를 주면 화면이 「아직 만드는 중」이라고 말한다 — 사실은 반대로
    // 끝났다가 사라진 것이라, 사용자가 기다리면 안 되고 다시 생성해야 한다.
    EXPORT_EXPIRED(HttpStatus.GONE, "다운로드 기한이 지났습니다. 다시 생성해 주세요."),

    // --- 대출 · 지원 제도 · 문서 ---
    LOAN_NOT_FOUND(HttpStatus.NOT_FOUND, "대출을 찾을 수 없습니다."),
    PROGRAM_NOT_FOUND(HttpStatus.NOT_FOUND, "지원 제도를 찾을 수 없습니다."),
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "문서를 찾을 수 없습니다."),

    // --- 공통 ---
    JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "작업을 찾을 수 없습니다."),
    /**
     * 스위퍼가 재시도 상한까지 쓴 작업을 실패로 확정할 때 {@code job.error_code} 에 담는다.
     *
     * <p>예외로 던지지 않으므로 여기 적은 HTTP 상태는 명목상 값이다 — 이 코드는
     * {@code GET /jobs/{jobId}} 응답의 {@code error.code} 로 200 과 함께 나간다(API 2.7).
     * {@code INTERNAL_ERROR} 로 뭉개지 않는 이유는 "시간 초과로 재시도까지 하고 포기함"과
     * "서버 버그"를 구분하는 것이 스위퍼가 존재하는 이유이기 때문이다.
     */
    JOB_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "작업이 제한 시간 내에 완료되지 않았습니다."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청값이 올바르지 않습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
