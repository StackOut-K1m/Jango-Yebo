package com.jangoyebo.forecast.planned;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.core.AccountExecutions;
import com.jangoyebo.core.fin.FinApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 예정 거래 자동 반영 (API 9.6).
 *
 * <p><b>금융망만 대역으로 바꾼다.</b> 이 테스트가 증명하려는 것은 「어떤 요청을 보내는가」가
 * 아니라 <b>「언제 보내고 언제 보내지 않는가」</b>이고, 그 판단은 전부 우리 쪽에 있다.
 * 요청 본문은 {@code DemandDepositClientTests} 가 본다.
 *
 * <p>스레드를 띄우지 않고 {@code runOnce()} 를 직접 부른다({@code JobWorkerTests} 와 같은
 * 이음매). 시계를 고정해 「오늘」과 「지금 몇 시」를 시험이 정한다.
 */
@NeedsDocker
@SpringBootTest
class PlannedExecutionWorkerTests {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

    @Autowired
    private PlannedExecutionService service;

    @Autowired
    private PlannedExecutionRepository executions;

    @Autowired
    private JdbcTemplate jdbc;

    private FakeAccountExecutions fin;
    private Long businessId;
    private Long accountId;
    private Long patternId;

    @BeforeEach
    void setUp() {
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, status)
                VALUES (?, 'hash', '김사장', 'ACTIVE') RETURNING id
                """, Long.class, "exec-" + System.nanoTime() + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name, taxation_type, status)
                VALUES (?, '카페 한잔', 'GENERAL', 'ACTIVE') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account (business_id, provider, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name,
                                          status, sync_status, auto_execute_planned)
                VALUES (?, 'FINOPENAPI', '999', 'enc', ?, '999-**-1234', '주계좌',
                        'CONNECTED', 'SUCCESS', TRUE) RETURNING id
                """, Long.class, businessId, "hash-" + System.nanoTime());
        patternId = jdbc.queryForObject("""
                INSERT INTO forecast.recurring_pattern
                       (business_id, account_id, name, flow_type, cadence_type,
                        expected_amount_min, expected_amount_max, confidence, status)
                VALUES (?, ?, '김영자', 'WITHDRAWAL', 'MONTHLY', 900000, 900000, 0.9, 'CONFIRMED')
                RETURNING id
                """, Long.class, businessId, accountId);

        fin = new FakeAccountExecutions();
        fin.accounts.add(new AccountExecutions.ExecutableAccount(accountId, businessId));
    }

    // --- 보내는 경우 ---------------------------------------------------------

    @Test
    void 기한이_된_예정거래를_보내고_동기화까지_건다() {
        Long plannedId = planned(patternId, TODAY, FlowType.WITHDRAWAL, "900000", "김영자");
        fin.respondWith(request -> executed(request, "FIN-1", TODAY));

        assertThat(worker(TODAY, LocalTime.of(10, 0)).runOnce()).isEqualTo(1);

        assertThat(fin.executed).hasSize(1);
        AccountExecutions.ExecutionRequest sent = fin.executed.getFirst();
        assertThat(sent.summary()).isEqualTo("김영자");
        assertThat(sent.amount()).isEqualByComparingTo("900000");
        assertThat(sent.amount().scale()).as("금융망은 정수 원만 받는다").isZero();
        assertThat(sent.dueDate()).isEqualTo(TODAY);

        PlannedExecution ledger = executions.findByPlannedTransactionId(plannedId).orElseThrow();
        assertThat(ledger.getStatus()).isEqualTo(PlannedExecutionStatus.EXECUTED);
        assertThat(ledger.getFinTransactionUniqueNo()).isEqualTo("FIN-1");
        assertThat(ledger.isAdopted()).isFalse();
        assertThat(ledger.getExecutorId()).as("어느 실행기가 보냈는지 남는다").isNotBlank();

        assertThat(fin.syncRequested).containsExactly(accountId);
    }

    /**
     * <b>입금이 먼저다.</b> 같은 날 정산 입금이 지출을 덮는 순서라야 시연 계좌가 잔액 부족으로
     * 멈추지 않는다 — 「카페 한잔」은 30일 저점이 −25만원으로 설계돼 있어 순서가 실제로 갈린다.
     */
    @Test
    void 입금을_출금보다_먼저_보낸다() {
        planned(patternId, TODAY, FlowType.WITHDRAWAL, "900000", "김영자");
        planned(pattern("신한카드", FlowType.DEPOSIT), TODAY, FlowType.DEPOSIT, "132000", "신한카드");
        fin.respondWith(request -> executed(request, "FIN-" + request.summary(), TODAY));

        worker(TODAY, LocalTime.of(10, 0)).runOnce();

        assertThat(fin.executed).extracting(AccountExecutions.ExecutionRequest::flowType)
                .containsExactly(FlowType.DEPOSIT, FlowType.WITHDRAWAL);
    }

    @Test
    void 지난_회차를_따라잡는다() {
        planned(patternId, TODAY.minusDays(2), FlowType.WITHDRAWAL, "900000", "김영자");
        fin.respondWith(request -> executed(request, "FIN-1", TODAY));

        // 이른 시각이어도 지난 회차는 보낸다 — 낮에 켠 노트북이 곧 집어야 한다.
        assertThat(worker(TODAY, LocalTime.of(6, 0)).runOnce()).isEqualTo(1);
        assertThat(fin.executed.getFirst().dueDate()).isEqualTo(TODAY.minusDays(2));
    }

    // --- 보내지 않는 경우 -----------------------------------------------------

    @Test
    void 당일자는_이른_시각에_보내지_않는다() {
        planned(patternId, TODAY, FlowType.WITHDRAWAL, "900000", "김영자");

        assertThat(worker(TODAY, LocalTime.of(8, 59)).runOnce()).isZero();
        assertThat(fin.executed).isEmpty();
    }

    @Test
    void 따라잡기_창_밖은_건드리지_않는다() {
        planned(patternId, TODAY.minusDays(4), FlowType.WITHDRAWAL, "900000", "김영자");

        assertThat(worker(TODAY, LocalTime.of(10, 0)).runOnce()).isZero();
        assertThat(fin.executed).isEmpty();
    }

    /**
     * <b>사용자가 등록한 일회성도 나간다.</b>
     *
     * <p>탐지된 후보에 「맞아요」를 누른 것보다 직접 적은 쪽이 오히려 더 명시적인 의사다.
     * 반면 세금·대출은 서버가 일정에서 만든 것이라 <b>사장님이 주장한 적 없는 돈</b>이다.
     */
    @Test
    void 사용자가_등록한_예정거래도_보낸다() {
        userPlanned(TODAY, FlowType.WITHDRAWAL, "1500000", "에스프레소 머신", accountId);
        fin.respondWith(request -> executed(request, "FIN-USER", TODAY));

        assertThat(worker(TODAY, LocalTime.of(10, 0)).runOnce()).isEqualTo(1);

        assertThat(fin.executed).hasSize(1);
        assertThat(fin.executed.getFirst().summary()).isEqualTo("에스프레소 머신");
    }

    @Test
    void 세금_예정거래는_보내지_않는다() {
        jdbc.update("""
                INSERT INTO forecast.planned_transaction
                       (business_id, account_id, source_type, name, expected_date,
                        original_expected_date, flow_type, amount_expected, amount_min, amount_max, status)
                VALUES (?, ?, 'TAX', '부가가치세', ?, ?, 'WITHDRAWAL', 870000, 870000, 870000, 'EXPECTED')
                """, businessId, accountId, TODAY, TODAY);

        assertThat(worker(TODAY, LocalTime.of(10, 0)).runOnce()).isZero();
        assertThat(fin.executed).isEmpty();
    }

    /**
     * 웹 등록 폼이 계좌를 보내기 전에 만들어진 행이다.
     *
     * <p>켠 계좌가 하나뿐이면 그 계좌 몫으로 본다 — 고를 여지가 없기 때문이다.
     */
    @Test
    void 계좌가_비어_있어도_켠_계좌가_하나면_보낸다() {
        Long plannedId = userPlanned(TODAY, FlowType.WITHDRAWAL, "500000", "보증금", null);
        fin.respondWith(request -> executed(request, "FIN-UNASSIGNED", TODAY));

        assertThat(worker(TODAY, LocalTime.of(10, 0)).runOnce()).isEqualTo(1);

        PlannedExecution ledger = executions.findByPlannedTransactionId(plannedId).orElseThrow();
        assertThat(ledger.getAccountId())
                .as("원장에는 예정 거래의 빈 계좌가 아니라 실제로 보낸 계좌가 남는다")
                .isEqualTo(accountId);
    }

    /** 켠 계좌가 둘이면 어느 통장에서 뺄지를 서버가 고르는 셈이라 건드리지 않는다. */
    @Test
    void 계좌가_비어_있고_켠_계좌가_둘이면_건너뛴다() {
        userPlanned(TODAY, FlowType.WITHDRAWAL, "500000", "보증금", null);
        Long second = jdbc.queryForObject("""
                INSERT INTO core.account (business_id, provider, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name,
                                          status, sync_status, auto_execute_planned)
                VALUES (?, 'FINOPENAPI', '999', 'enc', ?, '999-**-5678', '보조 통장',
                        'CONNECTED', 'SUCCESS', TRUE) RETURNING id
                """, Long.class, businessId, "hash-" + System.nanoTime());
        fin.accounts.add(new AccountExecutions.ExecutableAccount(second, businessId));

        assertThat(worker(TODAY, LocalTime.of(10, 0)).runOnce()).isZero();
        assertThat(fin.executed).isEmpty();
    }

    /**
     * <b>사전 조회 없이는 보내지 않는다.</b> 로컬과 배포가 같은 계좌를 볼 수 있어서 「이미
     * 나갔는가」의 유일한 근거가 금융망 거래내역이다. 그것을 못 읽으면 근거가 없다.
     */
    @Test
    void 사전_조회가_실패하면_아무것도_보내지_않는다() {
        planned(patternId, TODAY, FlowType.WITHDRAWAL, "900000", "김영자");
        fin.transactionsFail = true;

        assertThat(worker(TODAY, LocalTime.of(10, 0)).runOnce()).isZero();
        assertThat(fin.executed).isEmpty();
        assertThat(ledgers()).isEmpty();
    }

    @Test
    void 두_번_돌려도_한_번만_보낸다() {
        planned(patternId, TODAY, FlowType.WITHDRAWAL, "900000", "김영자");
        fin.respondWith(request -> executed(request, "FIN-1", TODAY));

        worker(TODAY, LocalTime.of(10, 0)).runOnce();
        worker(TODAY, LocalTime.of(10, 10)).runOnce();

        assertThat(fin.executed).hasSize(1);
    }

    // --- 이미 나간 거래를 줍는 경우 --------------------------------------------

    /**
     * 다른 서버가 이미 보냈다 — <b>호출하지 않고 주워 온다.</b> 이것이 로컬과 배포가 같은
     * 계좌를 볼 때 돈이 두 번 나가지 않는 첫 번째 이유다.
     */
    @Test
    void 금융망에_이미_같은_거래가_있으면_보내지_않고_줍는다() {
        Long plannedId = planned(patternId, TODAY, FlowType.WITHDRAWAL, "900000", "김영자");
        fin.entries.add(entry("OTHER-1", TODAY, FlowType.WITHDRAWAL, "900000", "김영자"));

        assertThat(worker(TODAY, LocalTime.of(10, 0)).runOnce()).isEqualTo(1);

        assertThat(fin.executed).as("호출하지 않는다").isEmpty();
        PlannedExecution ledger = executions.findByPlannedTransactionId(plannedId).orElseThrow();
        assertThat(ledger.getStatus()).isEqualTo(PlannedExecutionStatus.EXECUTED);
        assertThat(ledger.isAdopted()).isTrue();
        assertThat(ledger.getFinTransactionUniqueNo()).isEqualTo("OTHER-1");
        // 주운 것도 우리 DB 로 가져와야 곡선이 움직인다.
        assertThat(fin.syncRequested).containsExactly(accountId);
    }

    /** 예정일보다 <b>앞선</b> 거래는 이 회차가 아니다 — 금융망은 과거 날짜를 만들 수 없다. */
    @Test
    void 예정일보다_앞선_거래는_줍지_않는다() {
        planned(patternId, TODAY, FlowType.WITHDRAWAL, "900000", "김영자");
        fin.entries.add(entry("OLD-1", TODAY.minusDays(1), FlowType.WITHDRAWAL, "900000", "김영자"));
        fin.respondWith(request -> executed(request, "FIN-1", TODAY));

        worker(TODAY, LocalTime.of(10, 0)).runOnce();

        assertThat(fin.executed).hasSize(1);
    }

    /**
     * <b>같은 패턴의 같은 회차는 두 번 나가지 않는다.</b>
     *
     * <p>달력을 고치면 {@code refreshPlanned} 가 미래 예정 거래를 지우고 <b>새 id 로 다시
     * 만든다.</b> 그 틈에 이미 나간 회차의 짝이 하나 더 생기면, 예정 거래 단위 제약만으로는
     * 돈이 두 번 나간다. {@code uk_pe_pattern_due} 가 그 자리를 막는다.
     */
    @Test
    void 같은_패턴의_같은_회차는_두_번_나가지_않는다() {
        planned(patternId, TODAY, FlowType.WITHDRAWAL, "900000", "김영자");
        planned(patternId, TODAY, FlowType.WITHDRAWAL, "900000", "김영자");
        fin.respondWith(request -> executed(request, "FIN-1", TODAY));

        assertThat(worker(TODAY, LocalTime.of(10, 0)).runOnce()).isEqualTo(1);

        assertThat(fin.executed).hasSize(1);
        assertThat(ledgers()).hasSize(1);
    }

    // --- 실패 갈래 -----------------------------------------------------------

    /** 잔액 부족은 결함이 아니라 사건이다 — 정산 공백이 실제로 일어났다. 다음 날 다시 시도한다. */
    @Test
    void 잔액_부족은_다음_날_다시_시도한다() {
        Long plannedId = planned(patternId, TODAY, FlowType.WITHDRAWAL, "900000", "김영자");
        fin.respondWith(request -> failure(request, AccountExecutions.Outcome.INSUFFICIENT_BALANCE, "A1014"));

        worker(TODAY, LocalTime.of(10, 0)).runOnce();

        PlannedExecution ledger = executions.findByPlannedTransactionId(plannedId).orElseThrow();
        assertThat(ledger.getStatus()).isEqualTo(PlannedExecutionStatus.FAILED);
        assertThat(ledger.getRetryAfter()).isEqualTo(TODAY.plusDays(1));

        // 같은 날에는 다시 보내지 않는다.
        worker(TODAY, LocalTime.of(23, 0)).runOnce();
        assertThat(fin.executed).hasSize(1);

        // 다음 날에는 다시 보낸다.
        fin.respondWith(request -> executed(request, "FIN-LATE", TODAY.plusDays(1)));
        worker(TODAY.plusDays(1), LocalTime.of(10, 0)).runOnce();
        assertThat(fin.executed).hasSize(2);
        assertThat(executions.findByPlannedTransactionId(plannedId).orElseThrow().getStatus())
                .isEqualTo(PlannedExecutionStatus.EXECUTED);
    }

    @Test
    void 거절은_영구_실패다() {
        Long plannedId = planned(patternId, TODAY, FlowType.WITHDRAWAL, "900000", "김영자");
        fin.respondWith(request -> failure(request, AccountExecutions.Outcome.REJECTED, "A1003"));

        worker(TODAY, LocalTime.of(10, 0)).runOnce();
        worker(TODAY.plusDays(1), LocalTime.of(10, 0)).runOnce();

        assertThat(fin.executed).as("다시 보내지 않는다").hasSize(1);
        PlannedExecution ledger = executions.findByPlannedTransactionId(plannedId).orElseThrow();
        assertThat(ledger.getStatus()).isEqualTo(PlannedExecutionStatus.FAILED);
        assertThat(ledger.getRetryAfter()).isNull();
    }

    /**
     * 답을 못 받았다 — 나갔는지 모른다. {@code SENT} 로 남겨 <b>다음 차례가 거래내역부터</b>
     * 본다. 상한까지 가면 영구 실패로 접고 사람이 본다.
     */
    @Test
    void 답을_못_받으면_SENT_로_남고_상한에서_영구_실패가_된다() {
        Long plannedId = planned(patternId, TODAY, FlowType.WITHDRAWAL, "900000", "김영자");
        fin.respondWith(request -> failure(request, AccountExecutions.Outcome.TRANSIENT, null));

        worker(TODAY, LocalTime.of(10, 0)).runOnce();
        assertThat(executions.findByPlannedTransactionId(plannedId).orElseThrow().getStatus())
                .isEqualTo(PlannedExecutionStatus.SENT);

        worker(TODAY, LocalTime.of(10, 10)).runOnce();
        worker(TODAY, LocalTime.of(10, 20)).runOnce();

        PlannedExecution ledger = executions.findByPlannedTransactionId(plannedId).orElseThrow();
        assertThat(ledger.getStatus()).isEqualTo(PlannedExecutionStatus.FAILED);
        assertThat(ledger.getRetryAfter()).isNull();
        assertThat(fin.executed).hasSize(3);
    }

    /** 답을 못 받았지만 <b>사실은 나갔던</b> 경우. 다음 차례가 거래내역에서 주워 온다. */
    @Test
    void 답을_못_받은_건을_다음_차례에_줍는다() {
        Long plannedId = planned(patternId, TODAY, FlowType.WITHDRAWAL, "900000", "김영자");
        fin.respondWith(request -> failure(request, AccountExecutions.Outcome.TRANSIENT, null));
        worker(TODAY, LocalTime.of(10, 0)).runOnce();

        fin.entries.add(entry("WAS-SENT", TODAY, FlowType.WITHDRAWAL, "900000", "김영자"));
        assertThat(worker(TODAY, LocalTime.of(10, 10)).runOnce()).isEqualTo(1);

        assertThat(fin.executed).as("다시 보내지 않는다").hasSize(1);
        PlannedExecution ledger = executions.findByPlannedTransactionId(plannedId).orElseThrow();
        assertThat(ledger.getStatus()).isEqualTo(PlannedExecutionStatus.EXECUTED);
        assertThat(ledger.getFinTransactionUniqueNo()).isEqualTo("WAS-SENT");
    }

    /**
     * 중복 채번({@code H1007})은 <b>「이미 나갔다」는 뜻이다.</b> 새 번호로 다시 부르면 돈이 두
     * 번 나간다 — 거래내역을 다시 읽어 짝을 찾는다.
     */
    @Test
    void 중복_채번이면_거래내역에서_짝을_찾아_줍는다() {
        Long plannedId = planned(patternId, TODAY, FlowType.WITHDRAWAL, "900000", "김영자");
        fin.respondWith(request -> {
            // 호출 직후 그 거래가 거래내역에 나타난다 — 다른 서버가 방금 보낸 상황이다.
            fin.entries.add(entry("RACE-1", TODAY, FlowType.WITHDRAWAL, "900000", "김영자"));
            return failure(request, AccountExecutions.Outcome.DUPLICATE_UNIQUE_NO, "H1007");
        });

        assertThat(worker(TODAY, LocalTime.of(10, 0)).runOnce()).isEqualTo(1);

        PlannedExecution ledger = executions.findByPlannedTransactionId(plannedId).orElseThrow();
        assertThat(ledger.getStatus()).isEqualTo(PlannedExecutionStatus.EXECUTED);
        assertThat(ledger.isAdopted()).isTrue();
        assertThat(ledger.getFinTransactionUniqueNo()).isEqualTo("RACE-1");
    }

    @Test
    void 중복_채번인데_짝이_없으면_실패로_남긴다() {
        Long plannedId = planned(patternId, TODAY, FlowType.WITHDRAWAL, "900000", "김영자");
        fin.respondWith(request -> failure(request, AccountExecutions.Outcome.DUPLICATE_UNIQUE_NO, "H1007"));

        assertThat(worker(TODAY, LocalTime.of(10, 0)).runOnce()).isZero();

        PlannedExecution ledger = executions.findByPlannedTransactionId(plannedId).orElseThrow();
        assertThat(ledger.getStatus()).isEqualTo(PlannedExecutionStatus.FAILED);
        assertThat(ledger.getRetryAfter()).as("사람이 볼 때까지 다시 보내지 않는다").isNull();
    }

    /** 한 차례에 한 계좌로 보낼 수 있는 상한. 설정 사고가 대량 출금이 되지 않게 한다. */
    @Test
    void 한_차례_전송_상한을_넘기지_않는다() {
        for (int i = 0; i < 4; i++) {
            planned(patternId, TODAY.minusDays(i), FlowType.WITHDRAWAL,
                    String.valueOf(10000 + i), "김영자");
        }
        fin.respondWith(request -> executed(request, "FIN-" + request.summary(), TODAY));

        worker(TODAY, LocalTime.of(10, 0), 2).runOnce();

        assertThat(fin.executed).hasSize(2);
    }

    // --- 픽스처 --------------------------------------------------------------

    /** 이 테스트가 만든 계좌의 원장만. 테스트들이 한 컨텍스트·한 DB 를 공유한다. */
    private List<PlannedExecution> ledgers() {
        return executions.findByAccountIdAndStatusInOrderByDueDateAscIdAsc(
                accountId, List.of(PlannedExecutionStatus.values()));
    }

    private PlannedExecutionWorker worker(LocalDate today, LocalTime nowKst) {
        return worker(today, nowKst, 20);
    }

    private PlannedExecutionWorker worker(LocalDate today, LocalTime nowKst, int maxPerTick) {
        Clock clock = Clock.fixed(today.atTime(nowKst).atZone(KST).toInstant(), KST);
        PlannedExecutionProperties properties = new PlannedExecutionProperties(
                false, Duration.ofMinutes(10), Duration.ofSeconds(30),
                LocalTime.of(9, 0), 3, 3, maxPerTick);
        return new PlannedExecutionWorker(fin, service, properties, clock);
    }

    /** 반복 패턴 하나. 같은 계좌·방향·이름이 겹치면 안 된다({@code findBy...AndName}). */
    private Long pattern(String name, FlowType flowType) {
        return jdbc.queryForObject("""
                INSERT INTO forecast.recurring_pattern
                       (business_id, account_id, name, flow_type, cadence_type,
                        expected_amount_min, expected_amount_max, confidence, status)
                VALUES (?, ?, ?, ?, 'MONTHLY', 1, 99999999, 0.9, 'CONFIRMED')
                RETURNING id
                """, Long.class, businessId, accountId, name, flowType.name());
    }

    /** 사용자가 직접 등록한 일회성. 계좌는 폼이 보낼 수도, 안 보낼 수도 있다. */
    private Long userPlanned(LocalDate date, FlowType flowType, String amount, String name,
                             Long account) {
        return jdbc.queryForObject("""
                INSERT INTO forecast.planned_transaction
                       (business_id, account_id, source_type, name, expected_date,
                        original_expected_date, flow_type,
                        amount_expected, amount_min, amount_max, status)
                VALUES (?, ?, 'USER', ?, ?, ?, ?, ?, ?, ?, 'CONFIRMED')
                RETURNING id
                """, Long.class, businessId, account, name, date, date, flowType.name(),
                new BigDecimal(amount), new BigDecimal(amount), new BigDecimal(amount));
    }

    private Long planned(Long patternId, LocalDate date, FlowType flowType, String amount, String name) {
        return jdbc.queryForObject("""
                INSERT INTO forecast.planned_transaction
                       (business_id, account_id, source_type, recurring_pattern_id, name,
                        expected_date, original_expected_date, flow_type,
                        amount_expected, amount_min, amount_max, status)
                VALUES (?, ?, 'PATTERN', ?, ?, ?, ?, ?, ?, ?, ?, 'EXPECTED')
                RETURNING id
                """, Long.class, businessId, accountId, patternId, name, date, date,
                flowType.name(), new BigDecimal(amount), new BigDecimal(amount), new BigDecimal(amount));
    }

    private static AccountExecutions.LedgerEntry entry(String finId, LocalDate date, FlowType flowType,
                                                       String amount, String summary) {
        return new AccountExecutions.LedgerEntry(finId, date, flowType, new BigDecimal(amount), summary);
    }

    private static AccountExecutions.ExecutionResult executed(AccountExecutions.ExecutionRequest request,
                                                              String finId, LocalDate date) {
        return new AccountExecutions.ExecutionResult(AccountExecutions.Outcome.EXECUTED,
                "20260914000000000001", finId, date, "H0000", null);
    }

    private static AccountExecutions.ExecutionResult failure(AccountExecutions.ExecutionRequest request,
                                                             AccountExecutions.Outcome outcome,
                                                             String finCode) {
        return new AccountExecutions.ExecutionResult(outcome, "20260914000000000001",
                null, null, finCode, "대역이 만든 실패");
    }

    /**
     * 금융망 대역. 이 저장소는 Mockito 를 쓰지 않으므로 손으로 만든다
     * ({@code AccountLinkTests.FinStubs} 와 같은 방식).
     */
    private static final class FakeAccountExecutions implements AccountExecutions {

        private final List<ExecutableAccount> accounts = new ArrayList<>();
        private final List<LedgerEntry> entries = new ArrayList<>();
        private final List<ExecutionRequest> executed = new ArrayList<>();
        private final List<Long> syncRequested = new ArrayList<>();

        private boolean transactionsFail;
        private Function<ExecutionRequest, ExecutionResult> responder =
                request -> new ExecutionResult(Outcome.TRANSIENT, null, null, null, null, "응답 미설정");

        void respondWith(Function<ExecutionRequest, ExecutionResult> responder) {
            this.responder = responder;
        }

        @Override
        public List<ExecutableAccount> autoExecuteAccounts() {
            return List.copyOf(accounts);
        }

        @Override
        public List<LedgerEntry> transactions(Long accountId, LocalDate from, LocalDate to) {
            if (transactionsFail) {
                throw new FinApiException("금융망 조회 실패", new IllegalStateException("대역"));
            }
            return entries.stream()
                    .filter(entry -> !entry.date().isBefore(from) && !entry.date().isAfter(to))
                    .toList();
        }

        @Override
        public ExecutionResult execute(ExecutionRequest request) {
            executed.add(request);
            return responder.apply(request);
        }

        @Override
        public void requestSync(Long accountId) {
            syncRequested.add(accountId);
        }
    }
}
