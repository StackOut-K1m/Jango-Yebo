package com.jangoyebo.core.loan;

import com.jangoyebo.core.Loans;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * {@link Loans} 구현.
 *
 * <p>{@code AccountBalancesService} 와 같은 이유로 엔티티를 로드하지 않는다 — 부르는 쪽이
 * 쓰는 것은 계약 몇 값뿐이고, 곡선을 그릴 때마다 사업장의 모든 대출을 읽는다.
 *
 * <p><b>{@code CLOSED} 만 뺀다.</b> 연체는 남긴다 — 만기가 지나도 갚지 않은 돈이 있고,
 * 그것이 곡선에서 사라지면 없는 여유가 생긴다.
 */
@Service
public class LoansService implements Loans {

    private static final String SQL = """
            SELECT l.id,
                   l.withdrawal_account_id,
                   l.product_name,
                   l.repayment_cycle,
                   l.total_repayable,
                   l.interest_rate,
                   l.started_on,
                   l.maturity_date,
                   l.monthly_payment_day,
                   l.monthly_payment_amount,
                   l.status,
                   -- 연동이 끊긴 계좌는 잔액이 더 이상 갱신되지 않는다. 그 계좌의 지출만
                   -- 곡선에 넣으면 시작 잔액에 없는 돈이 빠져 없는 공백이 생긴다.
                   COALESCE(a.status = 'CONNECTED', FALSE) AS connected
              FROM core.loan l
              LEFT JOIN core.account a ON a.id = l.withdrawal_account_id
             WHERE l.business_id = ?
               AND l.status <> 'CLOSED'
             ORDER BY l.started_on, l.id
            """;

    private final JdbcTemplate jdbc;

    public LoansService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RepayingLoan> repayingLoans(Long businessId) {
        return jdbc.query(SQL, (rs, rowNum) -> map(rs), businessId);
    }

    private static RepayingLoan map(ResultSet rs) throws SQLException {
        return new RepayingLoan(
                rs.getLong("id"),
                rs.getObject("withdrawal_account_id", Long.class),
                rs.getString("product_name"),
                RepaymentCycle.valueOf(rs.getString("repayment_cycle")),
                rs.getBigDecimal("total_repayable"),
                rs.getBigDecimal("interest_rate"),
                rs.getObject("started_on", LocalDate.class),
                rs.getObject("maturity_date", LocalDate.class),
                rs.getObject("monthly_payment_day", Integer.class),
                rs.getBigDecimal("monthly_payment_amount"),
                "OVERDUE".equals(rs.getString("status")),
                rs.getBoolean("connected"));
    }
}
