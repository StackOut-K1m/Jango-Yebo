package com.jangoyebo.core.fin;

/**
 * 금융망 업무 API 요청 본문의 {@code Header} 객체 (기술스택 §3.6①).
 *
 * <p><b>회원 계열({@code MEMBER_01/02})은 이 객체를 쓰지 않는다</b> — {@code apiKey} 를 본문
 * 최상위에 놓는다(공통규약 23장). 두 형태를 하나로 뭉뚱그리면 안 된다.
 *
 * <p>필드 이름이 곧 금융망 계약이라 record 컴포넌트명을 그대로 직렬화한다. <b>이름을 바꾸면
 * 호출이 깨진다.</b>
 *
 * @param apiName                        호출 URI 의 마지막 path 와 같다.
 * @param transmissionDate               {@code YYYYMMDD}. <b>한국시간 기준.</b>
 * @param transmissionTime               {@code HHMMSS}. 금융망 서버 시각 ±5분을 벗어나면
 *                                       전부 {@code H1003} 이다 — 운영 서버 NTP 동기화가 필수다.
 * @param apiServiceCode                 {@code apiName} 과 항상 같은 값이다.
 * @param institutionTransactionUniqueNo <b>정확히 20자.</b> 요청마다 새로 채번한다
 *                                       ({@link FinApiHeaderFactory}).
 * @param userKey                        사용자마다 하나. 값이 없으면 빈 문자열로 나간다 —
 *                                       은행코드·상품 목록처럼 사용자별 데이터가 없는 조회만
 *                                       빈 값으로 통과한다 — <b>계좌를 지목하는 조회는 검증한다</b>(실측).
 */
public record FinApiHeader(String apiName,
                           String transmissionDate,
                           String transmissionTime,
                           String institutionCode,
                           String fintechAppNo,
                           String apiServiceCode,
                           String institutionTransactionUniqueNo,
                           String apiKey,
                           String userKey) {
}
