package com.jangoyebo.core.fin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 대출 조회 — 가입 목록({@code LOAN_08})과 상환 내역({@code LOAN_09}).
 *
 * <p>{@link DemandDepositClient} 와 같은 이유로 <b>전 필드가 {@code String}</b> 이다.
 * 금융망은 숫자를 문자열로 준다 — 워크북 타입표가 {@code Long}·{@code double}·{@code int} 라고
 * 적어 놓은 필드도 그렇다. 숫자 타입으로 받으면 형식이 조금만 바뀌어도 역직렬화가 통째로 깨진다.
 *
 * <h2>대출은 사용자 단위다</h2>
 *
 * <p>{@code LOAN_08} 은 {@code userKey} 로 조회하고 사업장 개념이 없다. 사장님이 가게를 둘
 * 가지고 있으면 두 가게의 대출이 <b>한 목록에 섞여서</b> 온다. 어느 사업장 것인지는
 * {@code withdrawalAccountNo} 가 우리 어느 계좌인지로 가른다 — 그 계좌를 우리가 모르면
 * 사업장을 특정할 수 없어 <b>어디에도 붙이지 않는다</b>.
 *
 * <h2>완납하면 목록에서 사라진다</h2>
 *
 * <p>{@code LOAN_09} 설명: 「상환이 완료되면 대출 계좌는 <b>자동해지</b>되고, 한번이라도 연체
 * 상태가 있다면 연체금을 납입할 때까지 계좌는 유지됩니다.」 그래서 {@code status} 에 「완납」이
 * 없다 — 다 갚은 대출은 <b>목록에서 빠지는 것</b>이 완납 신호다.
 */
@Component
public class LoanClient {

    private static final String DOMAIN = "loan";
    private static final String ACCOUNTS = "inquireLoanAccountList";
    private static final String REPAYMENTS = "inquireRepaymentRecords";
    private static final String PRODUCTS = "inquireLoanProductList";

    /** {@code LOAN_08}·{@code LOAN_09} 의 {@code status}. 금융망이 한국어로 준다. */
    public static final String 개설 = "개설";
    public static final String 상환중 = "상환중";
    public static final String 연체 = "연체";

    private final FinOpenApiClient client;

    public LoanClient(FinOpenApiClient client) {
        this.client = client;
    }

    /**
     * 이 사용자가 가입한 대출 전부 ({@code LOAN_08}).
     *
     * <p>사업장별로 나뉘지 않는다 — 부르는 쪽이 {@code withdrawalAccountNo} 로 가른다.
     *
     * @throws FinApiException 금융망 실패
     */
    public List<LoanAccountRec> inquireLoanAccounts(String userKey) {
        List<LoanAccountRec> recs = client.callEduForList(DOMAIN, ACCOUNTS, userKey,
                Map.of(), LoanAccountRec.class);
        return recs == null ? List.of() : recs;
    }

    /**
     * 한 대출의 상환 내역 ({@code LOAN_09}).
     *
     * <p><b>이미 시도한 회차만 온다.</b> 앞으로의 회차는 계약에서 계산한다
     * ({@code LoanRepaymentSchedule}).
     *
     * @param loanAccountNo 대출 계좌번호 — {@code LOAN_08} 의 {@code accountNo}
     */
    public RepaymentRec inquireRepayments(String userKey, String loanAccountNo) {
        return client.callEdu(DOMAIN, REPAYMENTS, userKey,
                Map.of("accountNo", loanAccountNo), RepaymentRec.class);
    }

    /**
     * 대출 상품 목록 ({@code LOAN_03}).
     *
     * <p><b>{@code userKey} 를 쓰지 않는다</b> — 팀 앱 전체가 같은 목록을 본다. 그래서
     * 누가 {@code LOAN_02} 로 만든 상품이든 다 보이고, <b>삭제 API 가 없어 지울 수도 없다.</b>
     * 화면에 내보낼지는 {@code LOAN_PRODUCT.recommendable} 이 따로 정한다.
     *
     * @throws FinApiException 금융망 실패
     */
    public List<LoanProductRec> inquireLoanProducts() {
        List<LoanProductRec> recs = client.callEduForList(DOMAIN, PRODUCTS, null,
                Map.of(), LoanProductRec.class);
        return recs == null ? List.of() : recs;
    }

    /**
     * {@code LOAN_03} 의 한 행.
     *
     * @param ratingName  <b>최소 가입 가능 등급</b>이다 — 그 등급 이상이면 가입할 수 있다
     * @param loanPeriod  일 단위. 금융망은 2~365 만 허용한다
     * @param repaymentMethodTypeName 「원리금균등상환」으로 오지만 <b>실제 계산은 단리 일할 +
     *                    매일 자동이체</b>다. 원문을 담되 화면에는 그대로 내지 않는다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoanProductRec(String accountTypeUniqueNo,
                                 String bankCode,
                                 String bankName,
                                 String accountName,
                                 String accountDescription,
                                 String ratingName,
                                 String loanTypeName,
                                 String loanPeriod,
                                 String minLoanBalance,
                                 String maxLoanBalance,
                                 String interestRate,
                                 String repaymentMethodTypeName) {
    }

    /**
     * {@code LOAN_08} 의 한 행.
     *
     * @param loanBalance <b>원금이 아니라 원금+이자 총액이다.</b> 워크북이 「(원금 + 이자)
     *                    대출금」이라고 적었다. 일 상환액은 이 값을 {@code loanPeriod} 로 나눈 것이다
     * @param loanDate    {@code YYYYMMDD}. 개설일이고, 첫 상환은 <b>다음날</b>이다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoanAccountRec(String accountNo,
                                 String accountName,
                                 String status,
                                 String accountTypeUniqueNo,
                                 String loanPeriod,
                                 String loanDate,
                                 String maturityDate,
                                 String loanBalance,
                                 String interestRate,
                                 String withdrawalAccountNo) {
    }

    /**
     * {@code LOAN_09} 응답.
     *
     * @param remainingLoanBalance 남은 (원금+이자). 만기 전 조기 판단에 쓸 수 있다
     * @param repaymentRecords     시도한 회차. 아직 한 번도 안 빠졌으면 비거나 없다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RepaymentRec(String accountNo,
                               String accountName,
                               String status,
                               String loanBalance,
                               String remainingLoanBalance,
                               String withdrawalAccountNo,
                               List<RepaymentRow> repaymentRecords) {

        public List<RepaymentRow> rows() {
            return repaymentRecords == null ? List.of() : repaymentRecords;
        }
    }

    /**
     * 한 회차.
     *
     * @param status        {@code SUCCESS} 또는 {@code FAIL}. 대출 계좌 상태와 다른 코드계다
     * @param paymentBalance 회차 금액. 매일 같은 값이다(마지막 회차만 나머지를 흡수)
     * @param failureReason 실패 시 「(대출) 자동이체 실패 잔액 부족」이 그대로 온다.
     *                      <b>우리가 막으려던 사건이 일어났다는 증거</b>라 원문을 남긴다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RepaymentRow(String installmentNumber,
                               String status,
                               String paymentBalance,
                               String repaymentAttemptDate,
                               String repaymentAttemptTime,
                               String repaymentActualDate,
                               String repaymentActualTime,
                               String failureReason) {

        public boolean isSuccess() {
            return "SUCCESS".equalsIgnoreCase(status);
        }
    }
}
