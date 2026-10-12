package com.jangoyebo.core;

import com.jangoyebo.common.domain.FlowType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 예정 거래를 금융망 계좌에 실제로 반영한다 (API 9.6) — {@code core} 모듈이 밖에 내놓는 공개 API.
 *
 * <p>{@link AccountBalances}·{@link CashGapAlerts} 와 같은 이유로 여기 있다. <b>무엇을 언제
 * 실행할지는 {@code forecast} 가 정하고</b>(예정 거래 원장을 가진 쪽이다), <b>계좌·계좌번호
 * 원문·{@code userKey}·Job 큐는 {@code core} 가 소유한다.</b> {@code core} 가 CLOSED 라
 * {@code forecast} 는 {@code core.fin}·{@code core.account}·{@code core.job} 을 직접 볼 수 없다.
 *
 * <h2>왜 실행기가 이 인터페이스를 통과해야 하는가</h2>
 *
 * <p>금융망 쓰기는 되돌릴 수 없다. 그래서 계좌번호를 복호화하고 채번을 정하고 실제 호출을
 * 하는 자리를 <b>계좌를 소유한 모듈 안에</b> 둔다. 호출부는 「이 예정 거래를 반영해 달라」만
 * 말하고, 그 요청이 실제로 어떤 번호로 어느 계좌에 나갔는지는 {@link ExecutionResult} 로만 안다.
 *
 * <h2>⚠ 이 인터페이스의 구현은 재시도하지 않는다</h2>
 *
 * <p>{@code H1007}(채번 중복)은 평소 「새 번호로 다시 부르면 된다」는 신호지만
 * ({@code FinResponseCode#isRetryable}), <b>여기서는 정반대다.</b> 채번이 결정적이라
 * 중복은 「같은 실행이 이미 나갔다」는 뜻이고, 새 번호로 다시 부르면 돈이 두 번 나간다.
 * 판정은 호출부가 금융망 거래내역을 다시 보고 한다({@link #transactions}).
 */
public interface AccountExecutions {

    /**
     * 자동 반영을 켠 계좌 (공통규약 23장).
     *
     * <p><b>{@code CONNECTED} 이고 플래그가 켜진 계좌만</b> 준다. 연동을 해제하면 플래그도
     * 함께 꺼지므로({@code Account#disconnect}) 여기 남지 않는다.
     */
    List<ExecutableAccount> autoExecuteAccounts();

    /**
     * 그 계좌의 금융망 거래내역 ({@code DD_12}).
     *
     * <p><b>보내기 전에 반드시 이것을 먼저 본다.</b> 로컬 DB 와 배포 DB 는 서로를 보지 못하고
     * 같은 시연 계좌를 가리킬 수 있어서, <b>두 서버가 공유하는 유일한 원장이 금융망 자신</b>이다.
     * 이미 같은 거래가 있으면 보내지 않고 주워 온다.
     *
     * <p><b>금융망 실패는 그대로 던진다.</b> 호출부는 그 계좌를 이번 차례에서 통째로
     * 건너뛴다 — 사전 조회 없이 보내면 중복을 막을 근거가 없다. 예외 타입은
     * {@code core.fin} 안이라 {@code forecast} 에서 보이지 않으므로 {@code RuntimeException}
     * 으로 받는다.
     */
    List<LedgerEntry> transactions(Long accountId, LocalDate from, LocalDate to);

    /**
     * 출금({@code DD_08})·입금({@code DD_09})을 실제로 일으킨다.
     *
     * <p>채번은 요청 내용에서 <b>결정적으로</b> 만든다 — 어느 서버가 언제 보내도 같은 번호라,
     * 두 번째 호출은 금융망이 {@code H1007} 로 거절한다. 그 거절이 마지막 방어선이다.
     */
    ExecutionResult execute(ExecutionRequest request);

    /**
     * 방금 만든 거래를 우리 DB 로 가져오도록 동기화를 접수한다 (API 7.3).
     *
     * <p>{@code requestedBy} 는 <b>사업장 소유자</b>로 채운다 — 동기화 핸들러가 그 사용자의
     * {@code userKey} 로 금융망을 부르기 때문이다. 이미 도는 동기화가 있으면 새로 넣지 않는다.
     */
    void requestSync(Long accountId);

    /** 자동 반영 대상 계좌. 사업장을 함께 주는 것은 호출부가 사업장 단위로 묶어 일하기 때문이다. */
    record ExecutableAccount(Long accountId, Long businessId) {
    }

    /**
     * 금융망 거래내역 한 줄. 채택 판정에 필요한 것만 담는다.
     *
     * @param finTransactionUniqueNo 금융망 거래고유번호. 우리 {@code external_transaction_id} 다
     * @param date                   거래일. <b>한국시간 벽시계 날짜</b>이며 예정일과 다를 수 있다
     * @param summary                적요. 없으면 빈 문자열
     */
    record LedgerEntry(String finTransactionUniqueNo, LocalDate date, FlowType flowType,
                       BigDecimal amount, String summary) {
    }

    /**
     * 실행 요청.
     *
     * @param dueDate 예정일. <b>채번의 재료이자 금융망에 보내지 않는 값</b>이다 — 금융망은 날짜
     *                파라미터가 없어 거래가 오늘로 찍힌다(기술스택 §4.1.1)
     * @param amount  <b>정수 원</b>({@code scale 0}, 양수). 금융망은 금액을 문자열로 받고
     *                소수점이 있으면 {@code Q1001} 이다
     * @param summary 적요. 금융망 통장에 그대로 남고 동기화 때 {@code description} 이 된다
     * @param ordinal 같은 {@code (예정일, 방향, 금액, 적요)} 가 여럿일 때의 순번. 채번을 가른다
     */
    record ExecutionRequest(Long accountId, LocalDate dueDate, FlowType flowType,
                            BigDecimal amount, String summary, int ordinal) {
    }

    /**
     * 실행 결과.
     *
     * @param finTransactionUniqueNo 금융망이 돌려준 거래고유번호. <b>응답에 없을 수 있다</b> —
     *                               그때는 호출부가 거래내역을 다시 보고 메운다
     */
    record ExecutionResult(Outcome outcome,
                           String institutionTransactionUniqueNo,
                           String finTransactionUniqueNo,
                           LocalDate transactionDate,
                           String finCode,
                           String message) {

        public boolean isExecuted() {
            return outcome == Outcome.EXECUTED;
        }
    }

    /** 실행 결과 갈래. 호출부의 재시도 정책이 이 값으로 갈린다(API 9.6 표). */
    enum Outcome {

        /** 나갔다. */
        EXECUTED,

        /**
         * 같은 채번이 이미 쓰였다({@code H1007}).
         *
         * <p><b>새 번호로 다시 부르지 않는다.</b> 채번이 결정적이라 이건 「이미 나갔다」는 뜻이고,
         * 거래내역을 다시 봐서 그 거래를 주워 오는 것이 올바른 처리다.
         */
        DUPLICATE_UNIQUE_NO,

        /**
         * 잔액이 모자란다({@code A1014}).
         *
         * <p><b>결함이 아니라 사건이다</b> — 정산 공백이 실제로 일어난 것이고, 이 서비스가
         * 예측하려는 바로 그 상황이다. 다음 날 다시 시도한다.
         */
        INSUFFICIENT_BALANCE,

        /** 요청이 거절됐다. 같은 요청을 다시 보내도 같은 답이 온다 — 사람이 봐야 한다. */
        REJECTED,

        /**
         * 금융망이 답을 주지 못했다(전송 오류·타임아웃·5xx·시계 오차).
         *
         * <p><b>나갔는지 안 나갔는지 모른다.</b> 다음 차례에 거래내역을 먼저 보고 판정한다.
         */
        TRANSIENT
    }
}
