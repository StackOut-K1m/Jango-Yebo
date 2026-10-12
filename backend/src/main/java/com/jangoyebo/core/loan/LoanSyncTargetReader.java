package com.jangoyebo.core.loan;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 일일 대출 동기화 대상 (P1-13).
 *
 * <p><b>전 사업장을 돌지 않는다.</b> 조건 둘을 건다.
 *
 * <ul>
 *   <li><b>소유자에게 이미 {@code finUserKey} 가 있어야 한다.</b> 이것이 없으면
 *       {@code FinUserKeyService.resolve} 가 <b>금융망에 회원을 새로 만든다</b> — 배치가
 *       외부 시스템에 계정을 찍고 다니면 안 된다. 사용자가 계좌를 연결할 때 생기는 것이지
 *       우리가 새벽에 만들 것이 아니다.
 *   <li><b>연결된 계좌가 하나는 있어야 한다.</b> 대출을 사업장에 배정하는 근거가 출금계좌라,
 *       계좌가 없으면 받아 와도 전부 {@code unattached} 로 떨어진다({@link LoanSyncHandler}).
 * </ul>
 *
 * <p>둘을 걸어도 대출이 없는 사용자는 걸러지지 않는다 — 대출 유무는 {@code LOAN_08} 을 불러야
 * 알 수 있다. 하루 한 번 빈 목록을 받는 비용은 감수한다.
 */
@Component
class LoanSyncTargetReader {

    private final JdbcTemplate jdbc;

    LoanSyncTargetReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    List<Target> findAll() {
        return jdbc.query("""
                SELECT b.id AS business_id, b.owner_user_id AS owner_user_id
                  FROM core.business b
                  JOIN core.users u ON u.id = b.owner_user_id
                 WHERE b.deleted_at IS NULL
                   AND u.fin_user_key IS NOT NULL
                   AND EXISTS (SELECT 1 FROM core.account a
                                WHERE a.business_id = b.id AND a.status = 'CONNECTED')
                 ORDER BY b.id
                """, (rs, i) -> new Target(rs.getLong("business_id"), rs.getLong("owner_user_id")));
    }

    /**
     * @param ownerUserId Job 의 {@code requested_by} 에 들어간다. {@link LoanSyncHandler} 가
     *                    이 값으로 {@code userKey} 를 정하므로 <b>비워 둘 수 없다</b> —
     *                    비면 그 Job 은 즉시 실패한다
     */
    record Target(Long businessId, Long ownerUserId) {
    }
}
