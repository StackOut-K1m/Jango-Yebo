package com.jangoyebo.core.support;

import com.jangoyebo.NeedsDocker;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지원사업 공고 매핑 (P1-15, V21).
 *
 * <p>공고는 <b>수집 → 구조화</b> 두 단계로 채워진다. 여기서 보는 것은 그 중간 상태가 실제로
 * 저장되는가와, <b>다시 구조화할지 말지를 무엇으로 정하는가</b>다 — 그 판단이 틀리면 매일
 * 1,400건어치 LLM 비용이 그대로 나간다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@Transactional
class SupportProgramTests {

    @Autowired private SupportProgramRepository programs;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;

    /** 수집 직후에는 자격도 기간도 없다. 그 상태로 저장돼야 한다. */
    @Test
    void savesACollectedProgramBeforeItIsStructured() {
        SupportProgram saved = programs.saveAndFlush(collected("PBLN_1"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.isStructured()).isFalse();
        assertThat(saved.getEligibility()).isEmpty();
        assertThat(saved.getStatus()).isEqualTo("UNKNOWN");
        assertThat(saved.getApplicationEndDate()).isNull();
    }

    /** JSONB 왕복. 중첩 배열이 그대로 돌아와야 17.2 의 excludes·uncertainConditions 가 산다. */
    @Test
    void roundTripsNestedEligibility() {
        SupportProgram program = programs.saveAndFlush(collected("PBLN_2"));
        program.applyStructuring("SUBSIDY", new BigDecimal("3000000"), null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 19), 30,
                Map.of("regions", List.of("광주"),
                        "excludes", List.of("휴·폐업자", "국세 체납자"),
                        "uncertainConditions", List.of("공동사업자 신청 가능 여부")));
        programs.flush();

        SupportProgram loaded = programs.findById(program.getId()).orElseThrow();
        assertThat(loaded.isStructured()).isTrue();
        assertThat(loaded.getMaxAmount()).isEqualByComparingTo("3000000");
        assertThat(loaded.getEligibility()).containsEntry("regions", List.of("광주"));
        assertThat(loaded.getEligibility().get("excludes"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("휴·폐업자", "국세 체납자");
    }

    /**
     * <b>원문이 그대로면 다시 구조화하지 않는다.</b>
     *
     * <p>매일 전량을 받아 upsert 하므로 대부분의 공고는 내용이 같다. 그때마다 LLM 을 태우면
     * 비용이 공고 수만큼 매일 나간다. 제목 정정은 다시 읽을 이유가 아니다.
     */
    @Test
    void reStructuresOnlyWhenTheRawTextChanged() {
        SupportProgram program = programs.saveAndFlush(collected("PBLN_3"));

        boolean unchanged = program.refreshFrom("제목이 조금 바뀜", "https://example.test/new",
                "광주광역시", "원문 그대로", "사업개요 한 줄", null, Instant.now());
        assertThat(unchanged).isFalse();
        assertThat(program.getTitle()).isEqualTo("제목이 조금 바뀜");
        assertThat(program.getOrganizer()).isEqualTo("광주광역시");

        boolean changed = program.refreshFrom("제목이 조금 바뀜", "https://example.test/new",
                "광주광역시", "공고가 연장되었습니다", "사업개요 한 줄", null, Instant.now());
        assertThat(changed).isTrue();
    }

    /** 구조화가 확신 못 한 조건이 하나라도 있으면 화면이 「기관 확인 필요」를 띄운다 (17.1). */
    @Test
    void flagsProgramsWhoseConditionsAreUncertain() {
        SupportProgram sure = programs.saveAndFlush(collected("PBLN_4"));
        sure.applyStructuring("FUNDING", null, null, null, null, null,
                Map.of("regions", List.of("광주"), "uncertainConditions", List.of()));

        SupportProgram unsure = programs.saveAndFlush(collected("PBLN_5"));
        unsure.applyStructuring("FUNDING", null, null, null, null, null,
                Map.of("regions", List.of("광주"),
                        "uncertainConditions", List.of("업력 산정 기준일")));

        assertThat(sure.needsVerification()).isFalse();
        assertThat(unsure.needsVerification()).isTrue();
    }

    /** 같은 공고를 두 출처가 각자의 ID 로 실어도 둘 다 들어간다 (기업마당 · 중소벤처24 중복). */
    @Test
    void keepsTheSameAnnouncementFromTwoSources() {
        String externalId = "PBLN_" + System.nanoTime();
        programs.saveAndFlush(SupportProgram.collected("BIZINFO", externalId, "같은 공고",
                "https://example.test/a", "광주광역시", "원문", "사업개요 한 줄", null, Instant.now()));
        programs.saveAndFlush(SupportProgram.collected("SMES", externalId, "같은 공고",
                "https://example.test/a", "광주광역시", "원문", "사업개요 한 줄", null, Instant.now()));

        assertThat(programs.findBySourceNameAndExternalId("BIZINFO", externalId)).isPresent();
        assertThat(programs.findBySourceNameAndExternalId("SMES", externalId)).isPresent();
    }

    /**
     * <b>낡은 모집 상태를 오늘 기준으로 되맞춘다.</b>
     *
     * <p>{@code status} 는 수집·구조화가 쓰는 순간의 스냅샷이다. 마감일이 지나도 저절로
     * 바뀌지 않고, <b>마감된 공고는 출처 목록에서 빠져 재수집되지도 않으므로</b> 스스로
     * 고쳐질 길이 없다 — 2026-09-18 배포 DB 에서 {@code 'OPEN'} 147건 중 16건이 이미 마감일을
     * 지나 있었다. 그 상태를 여기서 직접 만들어 둔다(공개 API 로는 만들 수 없다. 낡음은
     * 시간이 흘러서 생기는 것이라서다).
     */
    @Test
    void refreshesAStaleRecruitmentStatus() {
        SupportProgram program = programs.saveAndFlush(collected("PBLN_STALE"));
        program.applyPeriod(LocalDate.now().minusDays(30), LocalDate.now().minusDays(1));
        programs.flush();

        // 어제 마감됐는데 아무도 status 를 고쳐 주지 않은 행 — 배포 DB 에 실제로 있던 모양이다
        jdbc.update("UPDATE core.support_program SET status = 'OPEN' WHERE id = ?", program.getId());
        em.clear();

        SupportProgram stale = programs.findById(program.getId()).orElseThrow();
        assertThat(stale.getStatus()).isEqualTo("OPEN");

        assertThat(stale.refreshStatus()).isTrue();
        assertThat(stale.getStatus()).isEqualTo("CLOSED");
    }

    /**
     * 안 바뀐 행은 <b>바뀌었다고 보고하지 않는다.</b> 스케줄러가 매일 전량을 훑으므로,
     * 여기서 늘 {@code true} 를 내면 로그가 매일 「전량 갱신」이라고 거짓말한다.
     */
    @Test
    void reportsOnlyRealStatusChanges() {
        SupportProgram program = programs.saveAndFlush(collected("PBLN_FRESH"));
        program.applyPeriod(LocalDate.now().minusDays(30), LocalDate.now().plusDays(7));
        assertThat(program.getStatus()).isEqualTo("OPEN");

        assertThat(program.refreshStatus()).isFalse();
        assertThat(program.getStatus()).isEqualTo("OPEN");
    }

    private SupportProgram collected(String externalId) {
        return SupportProgram.collected("BIZINFO", externalId + System.nanoTime(),
                "2026년 광주 소상공인 임대료 특별지원", "https://example.test/original",
                "광주광역시", "원문 그대로", "사업개요 한 줄", null, Instant.now());
    }
}
