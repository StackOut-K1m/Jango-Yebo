package com.jangoyebo.core.fin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSAFY 금융망 단일 POST 클라이언트 (기술스택 §3.6①).
 *
 * <p><b>요청 형태가 두 가지다. 하나로 뭉뚱그리면 안 된다</b>(공통규약 23장) —
 * 그래서 메서드가 {@link #callEdu} · {@link #callMember} 로 갈려 있다.
 *
 * <table border="1">
 * <caption>두 계열</caption>
 * <tr><th>대상</th><th>경로</th><th>apiKey 위치</th><th>userKey</th></tr>
 * <tr><td>회원 계정 생성·조회 (MEMBER_01/02)</td><td>{@code /ssafy/api/v1/member/**}</td>
 *     <td>본문 최상위</td><td>없음 — 여기서 발급받는다</td></tr>
 * <tr><td>그 외 모든 업무 API</td><td>{@code /ssafy/api/v1/edu/**}</td>
 *     <td>{@code Header} 안</td><td>{@code Header} 안</td></tr>
 * </table>
 *
 * <p>{@code domain}·{@code apiName} 을 상수로 열거하지 않고 <b>호출부가 준다.</b> xlsx 에 없는
 * 경로 규칙이라 실측으로만 확정되는데, 없는 경로는 404 에 응답 형태까지 다르다
 * ({@code responseCode} 가 아니라 {@code code}/{@code payload}). 확인하지 않은 이름을 상수로
 * 올려 두면 "있는 줄 알았는데 없는" 목록이 생긴다 — 각 인터페이스를 붙일 때 그 자리에서 적는다.
 *
 * <p><b>요청 본문을 로그에 남기지 않는다.</b> {@code Header} 에 {@code apiKey} 와
 * {@code userKey} 가 들어 있고, {@code userKey} 는 사실상 계좌 접근권이다.
 */
public class FinOpenApiClient {

    private static final Logger log = LoggerFactory.getLogger(FinOpenApiClient.class);

    private static final String EDU_PREFIX = "/ssafy/api/v1/edu/";
    private static final String MEMBER_PREFIX = "/ssafy/api/v1/member/";

    private final RestClient client;
    private final FinApiHeaderFactory headerFactory;
    private final ObjectMapper objectMapper;

    public FinOpenApiClient(RestClient client, FinApiHeaderFactory headerFactory, ObjectMapper objectMapper) {
        this.client = client;
        this.headerFactory = headerFactory;
        this.objectMapper = objectMapper;
    }

    /**
     * 업무 API 호출. 응답 본문 전체를 검증한 뒤 그대로 준다.
     *
     * @param domain  경로의 도메인 구간 (예: {@code demandDeposit})
     * @param apiName 경로의 마지막 구간이자 {@code Header.apiName}
     * @param userKey 사용자 키. 조회 계열은 {@code null} 이어도 통하지만, 그 관대함 때문에
     *                잘못된 키가 계좌 생성(H1009)에서야 드러난다
     * @param payload {@code Header} 를 뺀 나머지 요청 필드. <b>금액은 전부 문자열이다</b> —
     *                숫자로 넣으면 Q1001 이다
     * @throws FinApiException 전송 실패 또는 {@code responseCode != H0000}
     */
    public Map<String, Object> callEdu(String domain, String apiName, String userKey,
                                       Map<String, Object> payload) {
        return callEdu(domain, apiName, userKey, null, payload);
    }

    /**
     * 채번을 <b>호출부가 정하는</b> 업무 API 호출 (API 9.6 예정 거래 자동 반영).
     *
     * <p>⚠ <b>{@code H1007} 을 재시도 신호로 읽지 않는다.</b> 평소 그 코드는 「새 번호로 다시
     * 부르면 된다」는 뜻이지만({@code FinResponseCode#isRetryable}), 결정적 채번에서는
     * 「같은 실행이 이미 나갔다」는 뜻이다. 새 번호로 다시 부르면 <b>돈이 두 번 나간다.</b>
     * 그래서 이 경로의 재시도 판정은 {@code FinApiException#isRetryable} 이 아니라
     * 호출부가 거래내역을 다시 보고 한다.
     *
     * @param institutionTransactionUniqueNo {@code null} 이면 평소처럼 채번한다
     */
    public Map<String, Object> callEdu(String domain, String apiName, String userKey,
                                       String institutionTransactionUniqueNo,
                                       Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Header", headerFactory.create(apiName, userKey, institutionTransactionUniqueNo));
        if (payload != null) {
            body.putAll(payload);
        }
        return post(EDU_PREFIX + domain + "/" + apiName, body, apiName);
    }

    /**
     * 업무 API 호출 후 {@code REC} 를 {@code recType} 으로 변환한다.
     *
     * <p>{@code recType} 에는 <b>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 를
     * 붙인다.</b> {@code REC} 는 우리가 쓰지 않는 필드를 늘 함께 보내고 금융망이 필드를 추가해도
     * 통보가 없다 — 우리 쪽 매퍼 설정에 기대면 그 설정이 바뀌는 날 조용히 깨진다
     * ({@code AiWorkerClient} 의 내부 record 들과 같은 이유).
     *
     * @return {@code REC} 가 없으면 {@code null}
     */
    public <T> T callEdu(String domain, String apiName, String userKey,
                         Map<String, Object> payload, Class<T> recType) {
        return callEdu(domain, apiName, userKey, null, payload, recType);
    }

    /**
     * 채번을 호출부가 정하는 호출 + {@code REC} 변환.
     *
     * <p><b>{@code REC} 가 객체일 수도 단일 원소 배열일 수도 있다.</b> 조회 계열은 실측으로
     * 형태를 확정했지만({@code DD_12} 는 객체) 쓰기 계열은 아직 실측이 없다 — 어느 쪽이 와도
     * 깨지지 않게 여기서 벗겨 낸다. 시드 스크립트도 같은 이유로 두 형태를 함께 받는다.
     */
    public <T> T callEdu(String domain, String apiName, String userKey,
                         String institutionTransactionUniqueNo,
                         Map<String, Object> payload, Class<T> recType) {
        Object rec = callEdu(domain, apiName, userKey, institutionTransactionUniqueNo, payload).get("REC");
        if (rec instanceof List<?> elements) {
            rec = elements.isEmpty() ? null : elements.getFirst();
        }
        return rec == null ? null : objectMapper.convertValue(rec, recType);
    }

    /**
     * {@code REC} 가 <b>배열</b>로 오는 업무 API. {@code inquireBankCodes} 가 그렇다(실측).
     *
     * <p>{@link #callEdu(String, String, String, Map, Class)} 의 {@code Class<T>} 로는
     * {@code List<T>} 를 표현할 수 없어 따로 둔다. 원소를 하나씩 변환하는 것은 제네릭 타입을
     * 조립하지 않고 끝내기 위해서다 — 원소 수가 수십 단위인 참조 데이터라 비용이 문제되지 않는다.
     *
     * @return {@code REC} 가 없으면 빈 목록
     * @throws FinApiException {@code REC} 가 배열이 아닐 때. 조용히 빈 목록을 주면 호출부가
     *                         "금융망에 데이터가 없다"로 오해한다
     */
    public <T> List<T> callEduForList(String domain, String apiName, String userKey,
                                      Map<String, Object> payload, Class<T> elementType) {
        Object rec = callEdu(domain, apiName, userKey, payload).get("REC");
        if (rec == null) {
            return List.of();
        }
        if (!(rec instanceof List<?> elements)) {
            throw new FinApiException(null, null,
                    "금융망 %s 의 REC 가 배열이 아니다 — 단건 조회라면 callEdu 를 쓴다".formatted(apiName));
        }
        return elements.stream().map(element -> objectMapper.convertValue(element, elementType)).toList();
    }

    /**
     * 회원 계열 호출. {@code Header} 객체가 없고 {@code apiKey} 가 본문 최상위로 간다.
     *
     * <p>응답에 {@code responseCode} 가 없다 — 성공은 HTTP 상태로만 판정한다. 그래서
     * {@link #callEdu} 와 검증 경로를 공유하지 않는다.
     *
     * @param subPath      {@code /ssafy/api/v1/member/} 뒤에 붙는 구간. 없으면 빈 문자열
     * @param payload      {@code apiKey} 를 뺀 나머지 요청 필드
     * @param responseType 응답 본문 전체를 담을 타입
     */
    public <T> T callMember(String subPath, Map<String, Object> payload, Class<T> responseType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiKey", headerFactory.apiKey());
        if (payload != null) {
            body.putAll(payload);
        }
        Map<String, Object> response = postRaw(MEMBER_PREFIX + subPath, body, "member/" + subPath);
        return objectMapper.convertValue(response, responseType);
    }

    /** 전송 → 상태코드 확인 → {@code responseCode} 확인. */
    private Map<String, Object> post(String path, Object body, String apiLabel) {
        Map<String, Object> response = postRaw(path, body, apiLabel);
        verifyResponseCode(response, apiLabel);
        return response;
    }

    /** 전송 → 상태코드 확인까지만. 회원 계열은 {@code responseCode} 가 없어 여기서 끝난다. */
    private Map<String, Object> postRaw(String path, Object body, String apiLabel) {
        try {
            Map<String, Object> parsed = client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        Map<String, Object> readBody = readJson(response);
                        HttpStatusCode status = response.getStatusCode();
                        if (status.isError()) {
                            throw toException(status, readBody, apiLabel);
                        }
                        return readBody;
                    });
            return parsed == null ? Map.of() : parsed;
        } catch (FinApiException e) {
            throw e;
        } catch (RestClientException e) {
            // 연결 실패·타임아웃. 금융망이 답을 준 적이 없다 — 일시적으로 본다.
            throw new FinApiException("금융망 호출에 실패했습니다: " + apiLabel, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            Map<String, Object> body = response.bodyTo(Map.class);
            return body == null ? Map.of() : body;
        } catch (RuntimeException e) {
            // JSON 이 아니거나 비어 있다. 상태코드만으로 판단한다.
            return Map.of();
        }
    }

    private static void verifyResponseCode(Map<String, Object> body, String apiLabel) {
        String code = responseCode(body);
        if (FinResponseCode.isSuccess(code)) {
            log.debug("금융망 {} 정상처리", apiLabel);
            return;
        }
        throw fail(code, null, body, apiLabel);
    }

    private static FinApiException toException(HttpStatusCode status, Map<String, Object> body, String apiLabel) {
        // 없는 경로는 404 에 응답 형태까지 다르다 — responseCode 가 아니라 code/payload 다.
        if (status.value() == 404 && responseCode(body) == null) {
            return new FinApiException(null, status,
                    "금융망에 없는 경로다: " + apiLabel + " — domain 과 apiName 을 확인한다");
        }
        return fail(responseCode(body), status, body, apiLabel);
    }

    private static FinApiException fail(String code, HttpStatusCode status,
                                        Map<String, Object> body, String apiLabel) {
        String message = nested(body, "responseMessage");
        if (message == null) {
            message = string(body, "responseMessage");
        }
        String detail = "금융망 %s 실패 — %s%s".formatted(
                apiLabel, code == null ? "코드 없음" : code, FinResponseCode.diagnosis(code));

        // 원본 코드를 반드시 남긴다. 매핑되지 않은 코드는 이 로그가 유일한 단서다(기술스택 §3.6④).
        if (FinResponseCode.isOurBug(code)) {
            log.error("{} / {}", detail, message);
        } else {
            log.warn("{} / {}", detail, message);
        }
        return new FinApiException(code, status, message == null ? detail : detail + ": " + message);
    }

    /** 성공은 {@code Header.responseCode}, 실패는 최상위로 오는 경우가 있어 둘 다 본다. */
    private static String responseCode(Map<String, Object> body) {
        String nested = nested(body, "responseCode");
        return nested != null ? nested : string(body, "responseCode");
    }

    private static String string(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String nested(Map<String, Object> body, String key) {
        return body.get("Header") instanceof Map<?, ?> header && header.get(key) != null
                ? String.valueOf(header.get(key))
                : null;
    }
}
