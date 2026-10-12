package com.jangoyebo.core.tenancy;

import com.jangoyebo.common.tenancy.BusinessOwnershipReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사업장 소유권 조회 ({@code core}가 제공하는 구현).
 *
 * <p>모든 보호 API가 요청마다 한 번씩 호출하므로 엔티티를 로드하지 않고
 * 인덱스({@code ix_business_owner})만 타는 단일 조회로 처리한다.
 */
@Component
public class JdbcBusinessOwnershipReader implements BusinessOwnershipReader {

    private static final String SQL = """
            SELECT EXISTS (
                SELECT 1 FROM core.business
                WHERE id = ? AND owner_user_id = ? AND deleted_at IS NULL
            )
            """;

    private final JdbcTemplate jdbc;

    public JdbcBusinessOwnershipReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOwnedBy(Long businessId, Long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(SQL, Boolean.class, businessId, userId));
    }
}
