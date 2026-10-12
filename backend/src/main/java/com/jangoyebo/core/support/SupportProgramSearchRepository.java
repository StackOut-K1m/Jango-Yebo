package com.jangoyebo.core.support;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

/**
 * 공고 검색 (17.1).
 *
 * <p><b>네이티브 쿼리다.</b> 자격 조건이 JSONB 안에 있고 지역·업종 판정이 배열 원소를 훑는
 * 일이라 JPQL 로는 쓸 수 없다. 이 저장소에서만 쓰는 예외이며 쓰기는 여기 두지 않는다.
 */
public interface SupportProgramSearchRepository extends Repository<SupportProgram, Long> {

    /**
     * 공고 검색.
     *
     * <p><b>필터는 「빼는」 쪽이 아니라 「남기는」 쪽으로 느슨하다.</b> 자격이 되는 공고를
     * 걸러 버리는 것이 엉뚱한 공고를 하나 더 보여 주는 것보다 나쁘다 — 사장님은 안 보인
     * 지원금이 있었다는 사실 자체를 모른다. 그래서 <b>모르면 남긴다</b>:
     *
     * <ul>
     *   <li>지역·업종 목록이 <b>비어 있으면 통과</b> — 공고가 제한을 안 걸었거나 구조화가
     *       못 읽은 것이고, 둘 다 「해당 없음」의 근거가 아니다</li>
     *   <li>「전국」·「전 업종」 표기는 <b>항상 통과</b></li>
     *   <li>부분 일치를 <b>양방향</b>으로 본다 — 기업마당 지역 태그가 「전남광주」라
     *       {@code '광주'} 로 물으면 한쪽만으로는 안 맞고, 반대로 「광주 서구」로 물을 때
     *       공고의 「광주」도 맞아야 한다</li>
     *   <li><b>지역을 두 벌로 받는다</b>({@code region}·{@code regionBroad}). 하나로는 안 된다 —
     *       「전북 군산시」를 가장 넓은 토막 「전북」으로만 줄이면 「군산시」 공고를 놓치고,
     *       전체 문자열만 쓰면 「전남광주」 같은 묶음 태그를 놓친다. <b>군산시 사장님이
     *       군산시 지원금을 못 보는 일이 실제로 있었다</b>(2026-09-17 실호출)</li>
     * </ul>
     *
     * <p><b>구조화되지 않은 공고는 나오지 않는다</b>({@code eligibility = '{}'}). 자격 조건을
     * 모르는 공고를 「받을 수 있는 지원금」으로 내보내면 안 된다.
     *
     * <p><b>마감된 공고도 기본으로 빠진다.</b> 지난 공고를 목록에 섞으면 사장님이 마감일을
     * 일일이 확인해야 한다. {@code includeClosed} 로 되살릴 수 있다.
     *
     * <p><b>마감 판정은 {@code status} 와 날짜를 함께 본다.</b> {@code status} 는 수집·구조화
     * 시점에 계산해 둔 스냅샷이라 달력이 넘어가도 저절로 바뀌지 않는다 — 마감돼 출처 목록에서
     * 빠진 공고는 재수집되지 않으니 {@code 'OPEN'} 인 채로 남는다. 그것만 믿으면 기본 검색에
     * 지난 공고가 섞이고 <b>{@code includeClosed} 는 아무 일도 하지 않는 것처럼 보인다</b>
     * (2026-09-18 배포 DB 실측 — {@code 'OPEN'} 147건 중 16건이 마감일을 지나 있었고
     * {@code 'CLOSED'} 행은 하나도 없었다). 스냅샷은 {@link SupportProgramStatusScheduler} 가
     * 매일 다시 맞추고, 그 사이에 벌어지는 틈은 여기 날짜 조건이 막는다.
     *
     * <p>맞춤 추천(17.3)이 {@code deadlineAfter} 에 오늘을 넘겨 온 것도 같은 이유다.
     */
    @Query(value = """
            SELECT * FROM core.support_program p
             WHERE p.eligibility <> '{}'::jsonb
               AND (:includeClosed = TRUE
                    OR (p.status <> 'CLOSED'
                        AND (p.application_end_date IS NULL
                             OR p.application_end_date >= CURRENT_DATE)))
               AND (CAST(:supportType AS text) IS NULL OR p.support_type = CAST(:supportType AS text))
               AND (CAST(:deadlineAfter AS date) IS NULL
                    OR p.application_end_date IS NULL
                    OR p.application_end_date >= CAST(:deadlineAfter AS date))
               AND (CAST(:keyword AS text) IS NULL
                    OR p.title ILIKE '%' || CAST(:keyword AS text) || '%'
                    OR COALESCE(p.organizer, '') ILIKE '%' || CAST(:keyword AS text) || '%')
               AND (CAST(:region AS text) IS NULL
                    OR jsonb_array_length(core.jsonb_text_array(p.eligibility -> 'regions')) = 0
                    OR EXISTS (
                        SELECT 1 FROM jsonb_array_elements_text(core.jsonb_text_array(p.eligibility -> 'regions')) AS r
                         WHERE r LIKE '%전국%'
                            OR r ILIKE '%' || CAST(:region AS text) || '%'
                            OR CAST(:region AS text) ILIKE '%' || r || '%'
                            OR r ILIKE '%' || CAST(:regionBroad AS text) || '%'
                            OR CAST(:regionBroad AS text) ILIKE '%' || r || '%'))
               AND (CAST(:industry AS text) IS NULL
                    OR jsonb_array_length(core.jsonb_text_array(p.eligibility -> 'industries')) = 0
                    OR EXISTS (
                        SELECT 1 FROM jsonb_array_elements_text(core.jsonb_text_array(p.eligibility -> 'industries')) AS i
                         WHERE i LIKE '%전 업종%'
                            OR i LIKE '%전업종%'
                            OR i ILIKE '%' || CAST(:industry AS text) || '%'
                            OR CAST(:industry AS text) ILIKE '%' || i || '%'))
             ORDER BY
               CASE WHEN CAST(:sort AS text) = 'COLLECTED_AT' THEN p.collected_at END DESC,
               CASE WHEN CAST(:sort AS text) <> 'COLLECTED_AT' THEN p.application_end_date END
                    ASC NULLS LAST,
               p.id DESC
            """,
            countQuery = """
            SELECT count(*) FROM core.support_program p
             WHERE p.eligibility <> '{}'::jsonb
               AND (:includeClosed = TRUE
                    OR (p.status <> 'CLOSED'
                        AND (p.application_end_date IS NULL
                             OR p.application_end_date >= CURRENT_DATE)))
               AND (CAST(:supportType AS text) IS NULL OR p.support_type = CAST(:supportType AS text))
               AND (CAST(:deadlineAfter AS date) IS NULL
                    OR p.application_end_date IS NULL
                    OR p.application_end_date >= CAST(:deadlineAfter AS date))
               AND (CAST(:keyword AS text) IS NULL
                    OR p.title ILIKE '%' || CAST(:keyword AS text) || '%'
                    OR COALESCE(p.organizer, '') ILIKE '%' || CAST(:keyword AS text) || '%')
               AND (CAST(:region AS text) IS NULL
                    OR jsonb_array_length(core.jsonb_text_array(p.eligibility -> 'regions')) = 0
                    OR EXISTS (
                        SELECT 1 FROM jsonb_array_elements_text(core.jsonb_text_array(p.eligibility -> 'regions')) AS r
                         WHERE r LIKE '%전국%'
                            OR r ILIKE '%' || CAST(:region AS text) || '%'
                            OR CAST(:region AS text) ILIKE '%' || r || '%'
                            OR r ILIKE '%' || CAST(:regionBroad AS text) || '%'
                            OR CAST(:regionBroad AS text) ILIKE '%' || r || '%'))
               AND (CAST(:industry AS text) IS NULL
                    OR jsonb_array_length(core.jsonb_text_array(p.eligibility -> 'industries')) = 0
                    OR EXISTS (
                        SELECT 1 FROM jsonb_array_elements_text(core.jsonb_text_array(p.eligibility -> 'industries')) AS i
                         WHERE i LIKE '%전 업종%'
                            OR i LIKE '%전업종%'
                            OR i ILIKE '%' || CAST(:industry AS text) || '%'
                            OR CAST(:industry AS text) ILIKE '%' || i || '%'))
            """,
            nativeQuery = true)
    Page<SupportProgram> search(@Param("keyword") String keyword,
                                @Param("region") String region,
                                @Param("regionBroad") String regionBroad,
                                @Param("industry") String industry,
                                @Param("supportType") String supportType,
                                @Param("deadlineAfter") LocalDate deadlineAfter,
                                @Param("includeClosed") boolean includeClosed,
                                @Param("sort") String sort,
                                Pageable pageable);
}
