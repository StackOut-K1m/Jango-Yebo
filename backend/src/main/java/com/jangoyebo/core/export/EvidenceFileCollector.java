package com.jangoyebo.core.export;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 패키지에 담을 증빙 원본 목록 (API 14.2 — {@code 08_증빙원본/}).
 *
 * <p><b>거래에 연결된 증빙만 담는다.</b> 올리기만 하고 아직 어느 거래에도 붙지 않은 증빙은
 * 이 기간의 자료인지 알 수 없다 — 06 시트가 「증빙 누락」으로 그 반대쪽을 짚어 준다.
 *
 * <p><b>지운 증빙(V18 소프트 삭제)은 뺀다.</b> 07 연결목록과 같은 기준이라 두 자료가 어긋나지
 * 않는다.
 */
@Component
public class EvidenceFileCollector {

    private final JdbcTemplate jdbc;

    public EvidenceFileCollector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EvidenceFile> collect(Long businessId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT DISTINCT e.id, e.storage_key, e.original_filename,
                       min(t.transaction_date) OVER (PARTITION BY e.id) AS first_date
                  FROM core.transaction_evidence te
                  JOIN core.transaction t ON t.id = te.transaction_id
                  JOIN core.evidence e    ON e.id = te.evidence_id
                 WHERE t.business_id = ? AND t.transaction_date BETWEEN ? AND ?
                   AND e.deleted_at IS NULL
                 ORDER BY first_date, e.id
                """, (rs, i) -> new EvidenceFile(
                        rs.getString("storage_key"),
                        entryName(rs.getString("first_date"), rs.getLong("id"),
                                rs.getString("original_filename"))),
                businessId, from, to);
    }

    /**
     * ZIP 안에서 쓸 이름.
     *
     * <p><b>증빙 id 를 넣는다.</b> 07 연결목록이 원본 파일명으로 가리키는데 같은 이름이 여러 번
     * 올라오는 일이 흔해서(카메라가 {@code IMG_0001.jpg} 를 계속 만든다), 이름만으로는 목록과
     * 맞춰 볼 수 없다. <b>그리고 같은 날 두 장이면 ZIP 이 같은 이름을 두 번 쓰려다 패키지 생성이
     * 통째로 실패한다</b> — 증빙 id 는 유일하므로 이 이름은 겹치지 않는다.
     *
     * <p><b>경로 문자를 지운다.</b> 원본 파일명은 사용자가 준 값이라 {@code ../} 가 들어 있으면
     * 푸는 쪽에서 폴더 밖에 쓰인다.
     */
    private static String entryName(String date, long evidenceId, String originalFilename) {
        String safe = originalFilename == null ? "증빙" : originalFilename;
        safe = safe.replaceAll("[\\\\/:*?\"<>|]", "_").replace("..", "_");
        return "%s_%d_%s".formatted(date, evidenceId, safe);
    }

    public record EvidenceFile(String storageKey, String entryName) {
    }
}
