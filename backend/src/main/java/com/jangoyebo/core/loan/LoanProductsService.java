package com.jangoyebo.core.loan;

import com.jangoyebo.core.LoanProducts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * {@link LoanProducts} 구현.
 *
 * <p>{@code recommendable} 부분 인덱스({@code ix_lp_recommendable})를 그대로 탄다.
 * 꺼진 상품이 대다수가 되는 것을 전제로 만든 인덱스다.
 */
@Service
public class LoanProductsService implements LoanProducts {

    private static final String SQL = """
            SELECT id, external_id, bank_name, name, loan_type_name, min_grade,
                   min_loan_balance, max_loan_balance, interest_rate, loan_period_days,
                   repayment_method
              FROM core.loan_product
             WHERE recommendable
             ORDER BY interest_rate, id
            """;

    private final JdbcTemplate jdbc;

    public LoanProductsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecommendableProduct> recommendable() {
        return jdbc.query(SQL, (rs, rowNum) -> new RecommendableProduct(
                rs.getLong("id"),
                rs.getString("external_id"),
                rs.getString("bank_name"),
                rs.getString("name"),
                rs.getString("loan_type_name"),
                rs.getString("min_grade"),
                rs.getBigDecimal("min_loan_balance"),
                rs.getBigDecimal("max_loan_balance"),
                rs.getBigDecimal("interest_rate"),
                rs.getInt("loan_period_days"),
                rs.getString("repayment_method")));
    }
}
