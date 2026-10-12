package com.jangoyebo.core.fin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 수시입출금 조회 — 거래내역({@code DD_12})과 잔액({@code DD_07}).
 *
 * <p><b>금융망은 숫자를 전부 문자열로 준다</b>(2026-09-08 실측). {@code transactionBalance} 뿐
 * 아니라 {@code totalCount}·{@code transactionUniqueNo}·{@code accountBalance} 도 그렇다.
 * 그래서 아래 record 들은 <b>전 필드가 {@code String}</b> 이다 — 숫자 타입으로 받으면 금융망이
 * 형식을 조금만 바꿔도 역직렬화가 통째로 깨진다. 변환은 {@code AccountSyncPolicy} 가 한다.
 */
@Component
public class DemandDepositClient {

    /** 실측 경로 (2026-09-08). */
    private static final String DOMAIN = "demandDeposit";
    private static final String HISTORY = "inquireTransactionHistoryList";
    private static final String BALANCE = "inquireDemandDepositAccountBalance";

    /** 실측 경로가 아니다 — 시드 스크립트({@code scripts/seed/fin.mjs})가 쓰던 이름을 옮겼다. */
    private static final String WITHDRAW = "updateDemandDepositAccountWithdrawal";
    private static final String DEPOSIT = "updateDemandDepositAccountDeposit";

    /** 입금·출금을 모두 받는다. 요청의 {@code M}/{@code D} 와 응답의 {@code 1}/{@code 2} 는 다른 코드계다. */
    private static final String ALL_TYPES = "A";

    /**
     * 거래고유번호 오름차순. 두 가지 이유로 {@code DESC} 가 아니다 —
     * 삽입 순서가 일정해야 동시 배치가 교착하지 않고, 오래된 것부터 넣는 편이 읽기 좋다.
     */
    private static final String ASCENDING = "ASC";

    private final FinOpenApiClient client;

    public DemandDepositClient(FinOpenApiClient client) {
        this.client = client;
    }

    /**
     * 한 구간의 거래를 가져온다. <b>금융망 조회에는 페이지네이션이 없어</b> 날짜로 자르는 것이
     * 유일한 수단이다 — 구간 분할은 {@code AccountSyncPolicy} 가 한다.
     *
     * @param startYmd {@code YYYYMMDD}, 포함
     * @param endYmd   {@code YYYYMMDD}, 포함
     * @throws FinApiException 금융망 실패
     */
    public List<TransactionRec> inquireTransactionHistory(String userKey, String accountNo,
                                                          String startYmd, String endYmd) {
        HistoryRec rec = client.callEdu(DOMAIN, HISTORY, userKey,
                Map.of("accountNo", accountNo,
                        "startDate", startYmd,
                        "endDate", endYmd,
                        "transactionType", ALL_TYPES,
                        "orderByType", ASCENDING),
                HistoryRec.class);

        // 거래가 없는 달이 흔하다. REC 는 오는데 list 가 비거나 없을 수 있다.
        return rec == null || rec.list() == null ? List.of() : rec.list();
    }

    /**
     * 계좌 잔액.
     *
     * <p><b>실패를 예외로 올리지 않고 빈 값으로 접는다.</b> 잔액을 못 받았다고 이미 저장한 거래를
     * 되돌릴 이유가 없다 — 동기화의 본체는 거래 수집이고 잔액은 곁다리다. 호출부가 로그만 남기고
     * 계속 간다.
     */
    public Optional<BigDecimal> inquireBalance(String userKey, String accountNo) {
        BalanceRec rec = client.callEdu(DOMAIN, BALANCE, userKey,
                Map.of("accountNo", accountNo), BalanceRec.class);

        if (rec == null || rec.accountBalance() == null || rec.accountBalance().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(rec.accountBalance().trim()));
    }

    /**
     * 출금 ({@code DD_08}).
     *
     * <p><b>상대 계좌가 필요 없다.</b> 이체({@code DD_10})로 우회하지 않아도 되는 이유이며,
     * 시연 계정을 하나만 두고도 지출을 만들 수 있는 근거다.
     *
     * <p>⚠ <b>날짜 파라미터가 없다.</b> 거래는 무조건 호출한 날로 찍힌다(기술스택 §4.1.1 실측).
     * 예정일에 맞춰 과거로 넣을 방법이 없으므로 <b>예정일에 부르는 것</b>이 유일한 방법이다.
     *
     * @param amountWon <b>문자열 정수 원.</b> 숫자로 보내면 {@code Q1001} 이다
     * @param summary   적요. 통장에 그대로 남고 동기화 때 {@code description} 이 된다.
     *                  <b>비우면 금융망이 기본 적요를 넣는다</b> — 우리 거래를 알아볼 수 없게 된다
     * @param uniqueNo  결정적 채번. {@code null} 이면 평소처럼 새로 채번한다
     * @return 금융망이 준 거래고유번호. <b>{@code REC} 가 없으면 {@code null}</b> 이라
     *         호출부가 거래내역으로 메운다
     * @throws FinApiException 금융망 실패. 잔액 부족은 {@code A1014} 다
     */
    public TransferRec withdraw(String userKey, String accountNo, String amountWon,
                                String summary, String uniqueNo) {
        return transfer(WITHDRAW, userKey, accountNo, amountWon, summary, uniqueNo);
    }

    /**
     * 입금 ({@code DD_09}). 요청 모양은 {@link #withdraw} 와 같다.
     *
     * <p>시드가 잔액을 맞출 때 쓰던 것과 같은 경로다. 자동 반영에서는 <b>매출성 입금</b>
     * (카드 정산·플랫폼 정산·현금 입금)을 재현하는 데 쓴다 — 지출만 나가면 시연 계좌가
     * 며칠 만에 바닥나 전부 {@code A1014} 가 된다.
     */
    public TransferRec deposit(String userKey, String accountNo, String amountWon,
                               String summary, String uniqueNo) {
        return transfer(DEPOSIT, userKey, accountNo, amountWon, summary, uniqueNo);
    }

    private TransferRec transfer(String apiName, String userKey, String accountNo,
                                 String amountWon, String summary, String uniqueNo) {
        return client.callEdu(DOMAIN, apiName, userKey, uniqueNo,
                Map.of("accountNo", accountNo,
                        "transactionBalance", amountWon,
                        "transactionSummary", summary),
                TransferRec.class);
    }

    /**
     * {@code DD_08}·{@code DD_09} 의 {@code REC} — <b>객체이고 두 필드뿐이다</b>(2026-09-14 실측).
     *
     * <p>{@code transactionDate} 는 <b>호출한 날</b>이다. 채번 접두사를 과거 날짜로 보내도
     * 그렇다(같은 실측) — 금융망에 과거 날짜 거래를 만들 수 없다는 §4.1.1 이 여기서도 그대로다.
     *
     * <p>그래도 {@code @JsonIgnoreProperties} 와 배열 해제를 남겨 둔다. 실측 한 번이 계약을
     * 영구히 보증하지는 않고, 값이 비면 호출부가 {@code DD_12} 로 메우면 그만이다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransferRec(String transactionUniqueNo, String transactionDate) {
    }

    /** {@code DD_12} 의 {@code REC} 는 배열이 아니라 객체다(실측). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record HistoryRec(String totalCount, List<TransactionRec> list) {
    }

    /**
     * @param transactionType   {@code "1"} 입금 / {@code "2"} 출금
     * @param transactionDate   {@code YYYYMMDD}, <b>한국시간 벽시계 날짜</b>
     * @param transactionTime   {@code HHMMSS}
     * @param transactionAccountNo 상대 계좌번호. <b>빈 문자열로 올 수 있다</b>(실측)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransactionRec(String transactionUniqueNo,
                                 String transactionDate,
                                 String transactionTime,
                                 String transactionType,
                                 String transactionBalance,
                                 String transactionAfterBalance,
                                 String transactionSummary,
                                 String transactionAccountNo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BalanceRec(String accountNo, String accountBalance) {
    }
}
