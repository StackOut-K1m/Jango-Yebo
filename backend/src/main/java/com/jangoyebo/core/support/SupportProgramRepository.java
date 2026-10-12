package com.jangoyebo.core.support;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 지원사업 공고 저장소 (P1-15).
 *
 * <p>모듈 밖으로 노출하지 않는다 — {@code core} 는 CLOSED 이고, 바깥은 서비스로만 통한다
 * (모듈 규약, {@code core/package-info.java}).
 */
public interface SupportProgramRepository extends JpaRepository<SupportProgram, Long> {

    /**
     * 재수집 upsert 의 조회 축. {@code uk_sp_source} 와 같은 키다.
     *
     * <p>매일 전량을 받아 이 키로 기존 행을 찾고, 없으면 새로 넣는다. 출처가 다르면 다른
     * 공고로 취급한다 — 기업마당과 중소벤처24 가 같은 공고를 각자의 ID 로 싣기 때문이다.
     */
    Optional<SupportProgram> findBySourceNameAndExternalId(String sourceName, String externalId);

    /** 한 번의 수집 배치가 받아온 목록을 통째로 대조할 때 쓴다. */
    List<SupportProgram> findBySourceNameAndExternalIdIn(String sourceName,
                                                         List<String> externalIds);
}
