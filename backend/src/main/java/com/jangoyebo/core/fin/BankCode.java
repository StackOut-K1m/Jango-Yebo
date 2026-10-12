package com.jangoyebo.core.fin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 금융망 은행코드 ({@code BANK_01} / {@code inquireBankCodes}).
 *
 * <p>요청·응답 필드명이 우리 API 7.2 의 {@code {bankCode, bankName}} 과 그대로 같아서
 * 별도 응답 DTO 를 두지 않는다 — 변환 계층을 하나 만들어 봐야 같은 값을 옮겨 담을 뿐이다.
 * 금융망이 필드를 늘리면 {@code ignoreUnknown} 이 받아낸다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BankCode(String bankCode, String bankName) {
}
