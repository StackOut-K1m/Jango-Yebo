package com.jangoyebo.core.support;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 서류 발급처 사전 조회 (17.4). 기준 데이터라 사업장 필터가 없다.
 *
 * <p><b>네이티브 쿼리를 쓴다.</b> 별칭이 {@code TEXT[]} 컬럼이라 JPQL 로 원소를 훑을 수 없다.
 * {@code unnest} 로 펼쳐 비교한다 — 사전은 수십 행 규모지만 17.2 가 공고를 열 때마다 부르는
 * 경로라 GIN 인덱스를 걸어 뒀다({@code ix_dg_match_keys}).
 */
public interface DocumentGuideRepository extends JpaRepository<DocumentGuide, Long> {

    /** 사전 전체. {@code programId} · {@code q} 가 없을 때 준다. */
    List<DocumentGuide> findByActiveTrueOrderByIdAsc();

    /**
     * 정규화된 이름이 <b>어느 행에 걸렸는지</b>를 짝으로 돌려준다.
     *
     * <p><b>행만 돌려주면 안 된다.</b> 「사업자등록증」이 표제어 「사업자등록증명」의 별칭으로
     * 걸리는 것이 이 표의 핵심인데, 결과에 행만 있으면 호출한 쪽이 그 짝을 복원하려고
     * 표제어와 다시 비교하게 되고 <b>별칭으로 걸린 것을 전부 버린다.</b> 별칭 매칭이 있으나
     * 마나가 되는 실패라 질의가 짝을 함께 낸다.
     *
     * <p><b>빈 목록으로 부르면 안 된다</b> — {@code IN ()} 은 SQL 오류다.
     * {@link DocumentGuideService} 가 앞에서 막는다.
     */
    // 별칭을 snake_case 로 둔다. Postgres 는 따옴표 없는 별칭을 소문자로 접으므로
    // `AS matchKey` 는 결과 라벨이 `matchkey` 가 되고, 그 형태는 Spring Data 의 투영 이름
    // 변환이 받아 주지 않는다. `match_key` → `getMatchKey()` 가 지원되는 경로다.
    @Query(value = """
            SELECT k AS match_key, g.id AS guide_id
              FROM core.document_guide g, unnest(g.match_keys) AS k
             WHERE g.active AND k IN (:keys)
             ORDER BY g.id
            """, nativeQuery = true)
    List<GuideMatch> findMatches(@Param("keys") Collection<String> keys);

    /**
     * 사용자 검색 (17.4 {@code ?q=}).
     *
     * <p>표제어·발급처·별칭을 <b>모두</b> 본다. 「사업자등록증」으로 찾아도
     * 「사업자등록증명」이 나와야 한다 — 사장님은 공고에 적힌 표기를 그대로 입력한다.
     */
    @Query(value = """
            SELECT g.* FROM core.document_guide g
             WHERE g.active
               AND (g.document_name ILIKE '%' || :q || '%'
                    OR g.issuer ILIKE '%' || :q || '%'
                    OR EXISTS (SELECT 1 FROM unnest(g.match_keys) AS k
                                WHERE k LIKE '%' || :normalized || '%'))
             ORDER BY g.id
            """, nativeQuery = true)
    List<DocumentGuide> searchByKeyword(@Param("q") String q,
                                        @Param("normalized") String normalized);

    /** 정규화된 이름 ↔ 사전 행 짝. */
    interface GuideMatch {

        String getMatchKey();

        Long getGuideId();
    }
}
