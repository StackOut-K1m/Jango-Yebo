package com.jangoyebo.core.loan;

import com.jangoyebo.core.fin.LoanClient.LoanProductRec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 금융망 대출 상품을 우리 테이블로 옮긴다 ({@code LOAN_03} → {@code LOAN_PRODUCT}, P1-14).
 *
 * <h2>{@code recommendable} 을 절대 덮어쓰지 않는다</h2>
 *
 * <p>이 클래스에서 제일 중요한 한 줄이다. upsert 의 갱신 목록에 {@code recommendable}·
 * {@code excluded_reason} 이 <b>없다.</b> 그래서
 *
 * <ul>
 *   <li>금융망에 새로 나타난 상품은 기본값 {@code FALSE} 로 들어온다 — 꺼진 채로</li>
 *   <li>사람이 켠 상품은 동기화가 몇 번을 지나가도 켜진 채로 남는다</li>
 * </ul>
 *
 * <p>덮어쓰면 <b>매 동기화마다 남의 테스트 상품이 화면에 되살아난다.</b> 금융망에는 대출 상품
 * 삭제 API 가 없고 팀 {@code apiKey} 를 가진 누구나 {@code LOAN_02} 로 상품을 만들 수 있다 —
 * 지금 목록에 있는 「한국은행 대출 상품 · 10% · 7일」이 그렇게 생긴 시험 상품이다.
 *
 * <h2>금융망이 안 주는 값은 지어내지 않는다</h2>
 *
 * <p>{@code LOAN_03} 은 등급별 자산 구간도, 상품이 언제 없어지는지도 주지 않는다. 우리가
 * 채우는 것은 받은 필드와 {@code collected_at} 뿐이다.
 */
@Service
public class LoanProductSyncService {

    private static final Logger log = LoggerFactory.getLogger(LoanProductSyncService.class);

    /**
     * <b>{@code recommendable}·{@code excluded_reason} 이 갱신 목록에 없다.</b> 그것이 이
     * 쿼리의 요점이다 — 위 설명 참조.
     */
    private static final String UPSERT = """
            INSERT INTO core.loan_product (provider, external_id, bank_code, bank_name, name,
                                           description, loan_type_name, min_grade,
                                           min_loan_balance, max_loan_balance,
                                           interest_rate, loan_period_days, repayment_method,
                                           collected_at)
            VALUES ('SSAFY_FIN', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (provider, external_id) DO UPDATE SET
                bank_code        = EXCLUDED.bank_code,
                bank_name        = EXCLUDED.bank_name,
                name             = EXCLUDED.name,
                description      = EXCLUDED.description,
                loan_type_name   = EXCLUDED.loan_type_name,
                min_grade        = EXCLUDED.min_grade,
                min_loan_balance = EXCLUDED.min_loan_balance,
                max_loan_balance = EXCLUDED.max_loan_balance,
                interest_rate    = EXCLUDED.interest_rate,
                loan_period_days = EXCLUDED.loan_period_days,
                repayment_method = EXCLUDED.repayment_method,
                collected_at     = now(),
                updated_at       = now()
            """;

    private final JdbcTemplate jdbc;

    public LoanProductSyncService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 받은 목록을 통째로 upsert 한다.
     *
     * <p><b>없어진 상품을 지우지 않는다.</b> 금융망에 삭제가 없어 목록에서 빠지는 일 자체가
     * 없고, 혹시 빠지더라도 <b>사람이 켜 둔 판단까지 같이 사라지는</b> 편이 더 나쁘다.
     *
     * @return 저장한 상품 수. 새로 들어온 것과 갱신된 것을 합친 값이다
     */
    @Transactional
    public int sync(List<LoanProductRec> recs) {
        int saved = 0;
        for (LoanProductRec rec : recs) {
            if (rec.accountTypeUniqueNo() == null || rec.ratingName() == null) {
                log.warn("상품 {} 을 건너뛴다 — 고유번호나 등급이 없다", rec.accountName());
                continue;
            }
            jdbc.update(UPSERT,
                    rec.accountTypeUniqueNo(),
                    rec.bankCode(),
                    nullSafe(rec.bankName(), "금융기관"),
                    nullSafe(rec.accountName(), "대출 상품"),
                    rec.accountDescription(),
                    nullSafe(rec.loanTypeName(), "신용대출"),
                    rec.ratingName().trim(),
                    decimal(rec.minLoanBalance()),
                    decimal(rec.maxLoanBalance()),
                    decimal(rec.interestRate()),
                    integer(rec.loanPeriod()),
                    nullSafe(rec.repaymentMethodTypeName(), "원리금균등상환"));
            saved++;
        }
        if (saved > 0) {
            log.info("대출 상품 {}건 동기화 — 추천 노출은 recommendable 이 켜진 것만", saved);
        }
        return saved;
    }

    private static String nullSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** 금융망은 숫자를 문자열로 준다. */
    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value.trim());
    }

    private static int integer(String value) {
        return value == null || value.isBlank() ? 0 : Integer.parseInt(value.trim());
    }
}
