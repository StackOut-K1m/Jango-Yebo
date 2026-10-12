package com.jangoyebo.core.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 계좌번호를 가리키는 값으로 바꾸는 규칙.
 *
 * <p>{@link AccountService} 안에 private 으로 있던 것을 꺼냈다. <b>대출 동기화가 같은 규칙을
 * 써야 하기 때문이다</b> — {@code LOAN_08} 의 {@code withdrawalAccountNo} 로 우리 계좌를 찾으려면
 * 연결할 때 만든 해시와 <b>같은 방법으로</b> 계산해야 한다. 두 벌로 두면 한쪽 규칙이 바뀌는
 * 날 대출이 조용히 어느 계좌에도 안 붙는다 — 오류가 아니라 침묵이라 알아채기 어렵다.
 */
final class AccountNumbers {

    private AccountNumbers() {
    }

    /**
     * 계좌번호에서 숫자만 남긴다. 해시·암호화·금융망 호출이 모두 이 값을 쓴다 —
     * 구분자는 표시({@code mask})에만 쓰이고 계좌를 가리키는 값에는 들어가지 않는다.
     */
    static String digitsOf(String rawAccountNo) {
        return rawAccountNo.replaceAll("[^0-9]", "");
    }

    /** 계좌번호 해시. 중복 연결 판정({@code ACCOUNT_ALREADY_LINKED})과 대출 계좌 매칭에 쓴다. */
    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 금융망이 준 계좌번호 원문을 우리 {@code account_number_hash} 로 바꾼다. */
    static String hashOf(String rawAccountNo) {
        return sha256(digitsOf(rawAccountNo));
    }
}
