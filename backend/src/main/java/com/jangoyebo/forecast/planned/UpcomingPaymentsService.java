package com.jangoyebo.forecast.planned;

import com.jangoyebo.core.UpcomingPayments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * {@link UpcomingPayments} 구현. {@code forecast} 가 소유한 예정 거래를 {@code core} 에
 * 한 줄만 내준다.
 */
@Service
public class UpcomingPaymentsService implements UpcomingPayments {

    private final JdbcTemplate jdbc;

    public UpcomingPaymentsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * <b>{@code EXPECTED} 만 본다.</b> 이미 실제 거래에 붙은({@code MATCHED}) 회차는 앞으로 나갈
     * 돈이 아니라 이미 나간 돈이다 — 그것을 「다음 지출」이라고 말하면 사장님이 두 번 준비한다.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<Payment> largestUpcoming(Long businessId, LocalDate after, int days) {
        List<Payment> rows = jdbc.query("""
                SELECT id, expected_date, name, amount_expected
                  FROM forecast.planned_transaction
                 WHERE business_id = ?
                   AND expected_date > ? AND expected_date <= ?
                   AND flow_type = 'WITHDRAWAL'
                   AND status = 'EXPECTED'
                 ORDER BY amount_expected DESC, id
                 LIMIT 1
                """, (rs, i) -> new Payment(rs.getLong("id"),
                        rs.getObject("expected_date", LocalDate.class),
                        rs.getString("name"), rs.getBigDecimal("amount_expected")),
                businessId, after, after.plusDays(days));
        return rows.stream().findFirst();
    }
}
