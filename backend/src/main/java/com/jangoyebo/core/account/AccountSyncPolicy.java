package com.jangoyebo.core.account;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.fin.DemandDepositClient.TransactionRec;
import com.jangoyebo.core.transaction.FinTransactionRow;
import com.jangoyebo.common.domain.FlowType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 동기화 규칙. <b>Spring 도 DB 도 HTTP 도 모르는 순수 함수만 둔다.</b>
 *
 * <p>기간 분할·1원 필터·매핑이 이 증분에서 가장 틀리기 쉬운 세 가지인데, 셋 다 부작용이 없어
 * 단위 테스트로 전부 잡힌다. 핸들러에 섞여 있으면 Docker 없이는 검증할 수 없게 된다.
 */
public final class AccountSyncPolicy {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HHmmss");

    private static final String DEPOSIT_CODE = "1";
    private static final String WITHDRAWAL_CODE = "2";

    /** 1원 인증 입금 판정에 쓰는 금액. */
    private static final BigDecimal ONE_WON = BigDecimal.ONE;

    private static final int DESCRIPTION_MAX = 255;
    private static final int COUNTERPARTY_MAX = 32;

    /**
     * 초기 적재 상한. 계좌 연결일이 하한이라 보통 구간이 1~2개지만, 시드가 {@code created_at} 을
     * 과거로 넣으면 구간 수가 폭발해 {@code stall-timeout}(10분)을 넘긴다. 24개월이면 최악 ~6분.
     */
    static final int MAX_MONTHS = 24;

    private AccountSyncPolicy() {
    }

    /** 금융망 조회 구간. {@code startDate}·{@code endDate} 는 둘 다 <b>포함</b>이다. */
    public record DateWindow(LocalDate start, LocalDate end) {

        public String startYmd() {
            return YMD.format(start);
        }

        public String endYmd() {
            return YMD.format(end);
        }
    }

    /**
     * 어느 날짜부터 가져올 것인가.
     *
     * <p><b>초기 적재의 하한은 계좌를 연결한 날이다.</b> 고정 기간("1년치")을 쓰면 계좌 개설 이전
     * 구간에서 빈 응답만 받는다. 연결 이전 거래를 안 가져오는 것은 손실이 아니라 이미 그어 둔
     * 경계다 — 과거는 CSV, 현재·미래는 금융망(기술스택 §4.1.1).
     *
     * <p>증분은 <b>마지막 성공 시각 전날</b>부터다. 하루를 겹쳐 받아 경계 거래를 놓치지 않고,
     * 중복은 {@code transactionUniqueNo} 가 막는다.
     */
    public static LocalDate syncFrom(Instant accountCreatedAt, Instant lastSyncedAt,
                                     LocalDate today, ZoneId zone) {
        LocalDate raw = lastSyncedAt != null
                ? LocalDate.ofInstant(lastSyncedAt, zone).minusDays(1)
                : LocalDate.ofInstant(accountCreatedAt, zone);

        // 앞을 자른다. 뒤를 자르면 최신 거래를 버리게 된다.
        LocalDate floor = today.minusMonths(MAX_MONTHS);
        return raw.isBefore(floor) ? floor : raw;
    }

    /**
     * {@code [from, to]} 를 한 달씩 덮는다. <b>겹치지도 비지도 않아야 한다.</b>
     *
     * <p>{@code plusMonths(1).minusDays(1)} 에서 <b>{@code -1일} 을 빼먹으면 구간이 하루씩 겹쳐</b>
     * 같은 거래를 두 번 받는다(양끝 포함이라). 중복은 흡수되지만 왕복이 늘고 카운트가 흐려진다.
     */
    public static List<DateWindow> monthlyWindows(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            // 시계 오차나 미래 created_at 방어. 빈 목록이면 호출부가 조용히 넘어간다.
            return List.of();
        }
        List<DateWindow> windows = new ArrayList<>();
        for (LocalDate cursor = from; !cursor.isAfter(to); ) {
            LocalDate end = cursor.plusMonths(1).minusDays(1);
            if (end.isAfter(to)) {
                end = to;
            }
            windows.add(new DateWindow(cursor, end));
            cursor = end.plusDays(1);
        }
        return windows;
    }

    /**
     * 1원 인증으로 우리가 만든 입금인가. <b>수집에서 제외한다.</b>
     *
     * <p>인증 아티팩트지 사업 거래가 아니다. 거르지 않으면 거래 목록에 보이고 분류를 타고
     * <b>잔액 곡선에까지 들어간다.</b> 계좌 연결마다 최소 1건, 재발송하면 더 쌓인다.
     *
     * <p><b>세 조건을 모두 본다.</b> 하나만 봐도 안 된다 — "1원 입금"만 보면 남이 보낸 1원까지
     * 지우고, "적요 시작"만 보면 상호명이 들어간 정상 입금을 지운다.
     *
     * <p>금액 비교에 {@code compareTo} 를 쓴다. {@code equals} 는 {@code numeric(19,4)} 왕복 후의
     * {@code 1.0000} 을 놓친다.
     */
    public static boolean isOneWonAuthDeposit(FinTransactionRow row, String authText) {
        if (row.flowType() != FlowType.DEPOSIT) {
            return false;
        }
        if (row.amount() == null || ONE_WON.compareTo(row.amount()) != 0) {
            return false;
        }
        // transactionSummary 는 생략 시 null 로 저장된다(실측).
        return row.description() != null && row.description().strip().startsWith(authText);
    }

    /**
     * 금융망 응답 한 줄을 삽입 행으로 옮긴다.
     *
     * <p>⚠ <b>{@code transactionDate} 를 {@code occurredAt} 에서 역산하지 않는다.</b> 금융망이
     * 한국시간 벽시계 날짜를 문자열로 주므로 그대로 쓴다. 역산하면 서버 시간대에 따라 하루가
     * 밀리는데, <b>개발 장비가 KST 라 그 버그는 테스트도 통과한다.</b>
     *
     * <p>{@code zone} 을 인자로 받고 {@code systemDefault()} 를 부르지 않는 것이 그 검증을
     * 가능하게 하는 전부다.
     *
     * @return 저장할 행. 저장할 수 없는 행이면 {@code null}
     * @throws BusinessException {@code transactionType} 이 계약에 없는 값일 때
     */
    public static FinTransactionRow toRow(Long businessId, Long accountId,
                                          TransactionRec rec, ZoneId zone) {
        FlowType flowType = flowType(rec.transactionType());
        BigDecimal amount = decimal(rec.transactionBalance());

        if (amount == null || amount.signum() <= 0) {
            // ck_tx_amount CHECK (amount > 0) 에 걸리면 청크 전체가 롤백된다.
            // 한 행 때문에 한 달치를 버리지 않는다. 호출부가 로그를 남긴다.
            return null;
        }

        LocalDate date = LocalDate.parse(rec.transactionDate(), YMD);
        LocalTime time = LocalTime.parse(rec.transactionTime(), HMS);
        Instant occurredAt = LocalDateTime.of(date, time).atZone(zone).toInstant();

        return new FinTransactionRow(businessId, accountId,
                rec.transactionUniqueNo(), occurredAt, date, flowType, amount,
                decimal(rec.transactionAfterBalance()),
                truncate(rec.transactionSummary(), DESCRIPTION_MAX),
                truncate(rec.transactionAccountNo(), COUNTERPARTY_MAX));
    }

    /**
     * 알 수 없는 값이면 <b>던진다.</b> 조용히 한쪽으로 접으면 방향이 통째로 뒤집힌 데이터가 쌓이고,
     * 계약 변경은 한 행이 아니라 전 행에 영향을 주므로 크게 실패하는 편이 맞다.
     */
    private static FlowType flowType(String transactionType) {
        return switch (transactionType == null ? "" : transactionType.trim()) {
            case DEPOSIT_CODE -> FlowType.DEPOSIT;
            case WITHDRAWAL_CODE -> FlowType.WITHDRAWAL;
            default -> throw new BusinessException(ErrorCode.FIN_API_FAILED,
                    "금융망 transactionType 이 계약에 없는 값이다: " + transactionType);
        };
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 빈 문자열은 null 로 정규화한다 — {@code transactionAccountNo} 가 그렇게 온다(실측). */
    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
