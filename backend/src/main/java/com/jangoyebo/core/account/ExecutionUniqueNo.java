package com.jangoyebo.core.account;

import com.jangoyebo.common.domain.FlowType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 예정 거래 자동 반영의 {@code institutionTransactionUniqueNo} 채번 — <b>순수 함수다</b>.
 *
 * <h2>왜 난수가 아니라 해시인가</h2>
 *
 * <p>평소 채번은 호출마다 새 난수다({@code FinApiHeaderFactory}). 중복이면 {@code H1007} 이라
 * <b>중복 거부 키</b>이지 멱등성 키가 아니기 때문이다. 자동 반영은 그 성질을 <b>거꾸로 쓴다.</b>
 *
 * <p>로컬 서버와 배포 서버는 DB 도 Redis 도 서로 보지 못하는데 <b>같은 금융망 계좌</b>를 가리킬
 * 수 있다(시드가 이메일로 {@code userKey} 를 뽑아 같은 계좌를 재사용한다). 두 서버가 같은 회차를
 * 같은 순간에 보내면 어떤 DB 잠금으로도 막을 수 없다. 그런데 <b>채번이 같으면</b> 금융망 자신이
 * 둘째 요청을 {@code H1007} 로 거절한다 — 우리가 가진 유일한 전역 잠금이다.
 *
 * <p>같은 이유로 <b>재전송에도 같은 번호</b>가 나온다. 응답을 못 받은 요청을 다시 보낼 때
 * 새 번호를 쓰면 돈이 두 번 나가는데, 이 함수는 저장된 값을 읽지 않고 요청 내용에서 다시
 * 만들어 내므로 그 실수를 할 수가 없다.
 *
 * <h2>모양</h2>
 *
 * <pre>
 *   yyyyMMdd(예정일)  +  SHA-256(계좌·예정일·방향·금액·적요·순번) 앞 6바이트를 12자리로
 *   ────────────────     ────────────────────────────────────────────────────────
 *          8자리                              12자리                = 정확히 20자
 * </pre>
 *
 * <p><b>접두사가 실행일이 아니라 예정일이다.</b> 실행일로 잡으면 하루 늦게 따라잡는 서버가
 * 다른 번호를 만들어 금융망이 거절하지 못한다 — 이중 실행을 막는 성질이 사라진다.
 *
 * <p><b>실측 확인</b>(2026-09-14). 접두사가 어제·그저께인 20자리 채번이 {@code H0000} 으로
 * 통과했고, 같은 번호를 다시 보내니 {@code H1007} 에 <b>잔액이 움직이지 않았다.</b> 금융망은
 * 접두사의 날짜를 검증하지 않고 번호 전체를 중복 판정에만 쓴다 — 이 함수가 기대는 성질 그대로다.
 *
 * <p>⚠ <b>충돌 확률.</b> 12자리는 10¹² 이라 한 예정일·한 계좌 안에서 서로 다른 두 실행이 같은
 * 번호를 얻을 일은 실질적으로 없다. 그래도 겹치면 {@code H1007} 이 나고 거래내역 대조에서
 * 짝을 못 찾아 <b>실패로 남는다</b> — 조용히 잘못된 거래를 만들지는 않는다.
 */
final class ExecutionUniqueNo {

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /** 해시에서 가져올 자릿수. 8(날짜) + 12 = 20 이라 {@code FinApiHeaderFactory} 의 길이와 맞는다. */
    private static final long MODULUS = 1_000_000_000_000L;

    private ExecutionUniqueNo() {
    }

    /**
     * @param accountNo 복호화된 계좌번호 원문. <b>해시 재료일 뿐 밖으로 나가지 않는다</b>
     * @param amount    정수 원. {@code toPlainString()} 으로 굳혀 {@code scale} 차이가 번호를
     *                  가르지 않게 한다 — {@code 900000} 과 {@code 900000.0000} 은 같은 실행이다
     */
    static String derive(String accountNo, LocalDate dueDate, FlowType flowType,
                         BigDecimal amount, String summary, int ordinal) {
        String material = String.join("|",
                accountNo,
                DATE.format(dueDate),
                flowType.name(),
                amount.stripTrailingZeros().toPlainString(),
                summary == null ? "" : summary,
                Integer.toString(ordinal));

        String suffix = String.format("%012d", unsigned48(sha256(material)) % MODULUS);
        return DATE.format(dueDate) + suffix;
    }

    /**
     * 앞 6바이트(48비트)를 부호 없는 값으로 읽는다.
     *
     * <p>8바이트를 쓰면 {@code long} 최상위 비트가 부호가 되어 음수가 나오고 {@code %} 가
     * 음수를 돌려준다 — {@code %012d} 가 부호를 자리로 세어 <b>21자</b>가 된다.
     * 48비트는 {@code 2^48 ≈ 2.8 × 10^14} 라 10¹² 보다 충분히 크다.
     */
    private static long unsigned48(byte[] digest) {
        long value = 0;
        for (int i = 0; i < 6; i++) {
            value = (value << 8) | (digest[i] & 0xFFL);
        }
        return value;
    }

    private static byte[] sha256(String material) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
