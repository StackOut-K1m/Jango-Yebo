package com.jangoyebo.core.account;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.core.account.AccountSyncPolicy.DateWindow;
import com.jangoyebo.core.fin.DemandDepositClient.TransactionRec;
import com.jangoyebo.core.transaction.FinTransactionRow;
import com.jangoyebo.common.domain.FlowType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 동기화 규칙 검증. Spring 도 Docker 도 쓰지 않는다.
 *
 * <p>이 증분에서 가장 틀리기 쉬운 셋이 전부 여기 있다 — 기간 분할(하루 겹침·월말 경계),
 * 1원 필터(조건 하나만 빠져도 실제 거래를 지운다), 시간대(개발 장비가 KST 라 잘못된 구현도
 * 통과한다).
 */
class AccountSyncPolicyTests {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String AUTH_TEXT = "잔고예보";

    // --- 기간 분할 --------------------------------------------------------

    /** 시계 오차나 미래 {@code created_at} 방어. 호출부가 조용히 넘어가야 한다. */
    @Test
    void producesNoWindowWhenRangeIsInverted() {
        assertThat(AccountSyncPolicy.monthlyWindows(
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 8))).isEmpty();
        assertThat(AccountSyncPolicy.monthlyWindows(null, LocalDate.of(2026, 9, 8))).isEmpty();
    }

    /** 계좌를 오늘 연결한 경우 — 시연 계정이 늘 이 모양이다. */
    @Test
    void producesOneWindowForASingleDay() {
        LocalDate today = LocalDate.of(2026, 9, 8);

        assertThat(AccountSyncPolicy.monthlyWindows(today, today))
                .containsExactly(new DateWindow(today, today));
    }

    /**
     * <b>겹치지도 비지도 않아야 한다.</b> {@code -1일} 을 빼먹으면 구간이 하루씩 겹쳐 같은 거래를
     * 두 번 받는다(양끝 포함이라).
     */
    @Test
    void coversTheRangeWithoutOverlapOrGap() {
        LocalDate from = LocalDate.of(2026, 6, 10);
        LocalDate to = LocalDate.of(2026, 9, 8);

        List<DateWindow> windows = AccountSyncPolicy.monthlyWindows(from, to);

        assertThat(windows.getFirst().start()).isEqualTo(from);
        assertThat(windows.getLast().end()).isEqualTo(to);
        for (int i = 1; i < windows.size(); i++) {
            assertThat(windows.get(i).start())
                    .as("%d번째 구간은 앞 구간 끝 다음 날에서 시작해야 한다", i)
                    .isEqualTo(windows.get(i - 1).end().plusDays(1));
        }
    }

    /**
     * 월말 경계 — {@code plusMonths(1)} 이 짧은 달로 클램프되는 자리라 하루가 새기 쉽다.
     * {@code 01-31 + 1개월 = 02-28}(2026년은 평년) 이므로 첫 구간은 {@code 02-27} 에 끝난다.
     */
    @Test
    void handlesMonthEndClamping() {
        List<DateWindow> windows = AccountSyncPolicy.monthlyWindows(
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 4, 30));

        assertThat(windows).startsWith(
                new DateWindow(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 27)),
                new DateWindow(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 27)));
    }

    // --- 시작일 ----------------------------------------------------------

    @Test
    void startsFromTheAccountLinkDateOnFirstSync() {
        Instant createdAt = Instant.parse("2026-09-07T23:00:00Z");   // KST 로는 09-08

        assertThat(AccountSyncPolicy.syncFrom(createdAt, null, LocalDate.of(2026, 9, 8), KST))
                .isEqualTo(LocalDate.of(2026, 9, 8));
    }

    /** 증분은 하루를 겹쳐 받는다. 경계 거래를 놓치지 않고, 중복은 유일 인덱스가 막는다. */
    @Test
    void startsFromTheDayBeforeTheLastSuccessOnIncrementalSync() {
        Instant createdAt = Instant.parse("2026-06-01T00:00:00Z");
        Instant lastSynced = Instant.parse("2026-09-07T23:00:00Z");   // KST 09-08

        assertThat(AccountSyncPolicy.syncFrom(createdAt, lastSynced, LocalDate.of(2026, 9, 8), KST))
                .isEqualTo(LocalDate.of(2026, 9, 7));
    }

    /** 상한은 <b>앞을</b> 자른다. 뒤를 자르면 최신 거래를 버린다. */
    @Test
    void clampsTheOldestBoundNotTheNewest() {
        LocalDate today = LocalDate.of(2026, 9, 8);
        Instant ancient = Instant.parse("2015-01-01T00:00:00Z");

        LocalDate from = AccountSyncPolicy.syncFrom(ancient, null, today, KST);

        assertThat(from).isEqualTo(today.minusMonths(AccountSyncPolicy.MAX_MONTHS));
        // 양끝 포함이라 마지막 하루가 자기 구간을 갖는다 — 24개월이면 24 + 1 이다.
        // 확인하려는 것은 정확한 개수가 아니라 왕복 수에 상한이 있다는 것이다.
        assertThat(AccountSyncPolicy.monthlyWindows(from, today))
                .hasSize(AccountSyncPolicy.MAX_MONTHS + 1);
    }

    // --- 1원 인증 필터 ----------------------------------------------------

    private FinTransactionRow row(FlowType flowType, String amount, String description) {
        return new FinTransactionRow(1L, 1L, "191471", Instant.EPOCH, LocalDate.EPOCH,
                flowType, new BigDecimal(amount), null, description, null);
    }

    @Test
    void filtersOutTheOneWonAuthDeposit() {
        assertThat(AccountSyncPolicy.isOneWonAuthDeposit(
                row(FlowType.DEPOSIT, "1", "잔고예보 4653"), AUTH_TEXT)).isTrue();
    }

    /**
     * 세 조건을 <b>각각 하나씩 무너뜨려</b> 모두 필요함을 보인다. 하나만 보면 실제 거래를 지운다.
     */
    @Test
    void keepsRowsThatBreakAnySingleCondition() {
        assertThat(AccountSyncPolicy.isOneWonAuthDeposit(
                row(FlowType.WITHDRAWAL, "1", "잔고예보 4653"), AUTH_TEXT))
                .as("출금 1원은 우리 인증이 아니다").isFalse();

        assertThat(AccountSyncPolicy.isOneWonAuthDeposit(
                row(FlowType.DEPOSIT, "1000", "잔고예보 4653"), AUTH_TEXT))
                .as("금액이 다르면 인증이 아니다").isFalse();

        assertThat(AccountSyncPolicy.isOneWonAuthDeposit(
                row(FlowType.DEPOSIT, "1", "수수료 환급"), AUTH_TEXT))
                .as("적요가 다르면 인증이 아니다").isFalse();
    }

    /** {@code transactionSummary} 는 생략 시 null 로 저장된다(실측). NPE 가 나면 안 된다. */
    @Test
    void survivesNullDescription() {
        assertThat(AccountSyncPolicy.isOneWonAuthDeposit(
                row(FlowType.DEPOSIT, "1", null), AUTH_TEXT)).isFalse();
    }

    /** {@code equals} 로 비교하면 {@code numeric(19,4)} 왕복 후의 {@code 1.0000} 을 놓친다. */
    @Test
    void comparesAmountByValueNotScale() {
        assertThat(AccountSyncPolicy.isOneWonAuthDeposit(
                row(FlowType.DEPOSIT, "1.0000", "잔고예보 4653"), AUTH_TEXT)).isTrue();
    }

    // --- 매핑과 시간대 ----------------------------------------------------

    private TransactionRec rec(String type, String balance, String summary, String counterparty) {
        return new TransactionRec("191471", "20260908", "010000", type,
                balance, "5", summary, counterparty);
    }

    /**
     * <b>이 테스트가 이 클래스의 핵심이다.</b> 시간대를 바꾸면 {@code occurredAt} 은 달라지지만
     * {@code transactionDate} 는 <b>달라지지 않아야</b> 한다 — 그게 "역산하지 않았다"의 증거다.
     * 역산하는 구현은 개발 장비(KST)에서 이 단정 없이는 통과한다.
     */
    @Test
    void keepsTheWallClockDateRegardlessOfZone() {
        TransactionRec rec = rec("1", "1000", "정산", "0011234567890123");

        FinTransactionRow inKst = AccountSyncPolicy.toRow(1L, 2L, rec, KST);
        FinTransactionRow inUtc = AccountSyncPolicy.toRow(1L, 2L, rec, ZoneOffset.UTC);

        assertThat(inKst.occurredAt()).isEqualTo(Instant.parse("2026-09-07T16:00:00Z"));
        assertThat(inUtc.occurredAt()).isEqualTo(Instant.parse("2026-09-08T01:00:00Z"));
        assertThat(inKst.transactionDate())
                .as("벽시계 날짜는 시간대와 무관하다")
                .isEqualTo(LocalDate.of(2026, 9, 8))
                .isEqualTo(inUtc.transactionDate());
    }

    @Test
    void mapsDirectionAndAmounts() {
        FinTransactionRow deposit = AccountSyncPolicy.toRow(1L, 2L, rec("1", "1000", "정산", ""), KST);
        FinTransactionRow withdrawal = AccountSyncPolicy.toRow(1L, 2L, rec("2", "300", "월세", null), KST);

        assertThat(deposit.flowType()).isEqualTo(FlowType.DEPOSIT);
        assertThat(deposit.amount()).isEqualByComparingTo("1000");
        assertThat(deposit.balanceAfter()).isEqualByComparingTo("5");
        assertThat(deposit.externalTransactionId()).isEqualTo("191471");
        assertThat(withdrawal.flowType()).isEqualTo(FlowType.WITHDRAWAL);

        assertThat(deposit.counterpartyAccountNo())
                .as("빈 문자열은 null 로 정규화한다").isNull();
        assertThat(withdrawal.counterpartyAccountNo()).isNull();
    }

    /**
     * 조용히 한쪽으로 접으면 <b>방향이 통째로 뒤집힌 데이터</b>가 쌓인다.
     * 계약 변경은 전 행에 영향을 주므로 크게 실패하는 편이 맞다.
     */
    @Test
    void refusesUnknownTransactionType() {
        assertThatThrownBy(() -> AccountSyncPolicy.toRow(1L, 2L, rec("9", "1000", "정산", null), KST))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("transactionType");
    }

    /** {@code ck_tx_amount CHECK (amount > 0)} 위반은 청크 전체를 롤백시킨다. 한 행만 버린다. */
    @Test
    void skipsRowsThatWouldViolateTheAmountCheck() {
        assertThat(AccountSyncPolicy.toRow(1L, 2L, rec("1", "0", "정산", null), KST)).isNull();
        assertThat(AccountSyncPolicy.toRow(1L, 2L, rec("1", "-5", "정산", null), KST)).isNull();
        assertThat(AccountSyncPolicy.toRow(1L, 2L, rec("1", "", "정산", null), KST)).isNull();
    }

    @Test
    void truncatesOverlongTextToTheColumnWidth() {
        String long300 = "가".repeat(300);
        FinTransactionRow mapped = AccountSyncPolicy.toRow(1L, 2L,
                rec("1", "1000", long300, "0".repeat(40)), KST);

        assertThat(mapped.description()).hasSize(255);
        assertThat(mapped.counterpartyAccountNo()).hasSize(32);
    }
}
