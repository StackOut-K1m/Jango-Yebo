package com.jangoyebo.core.support;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 공고 배치 적재 (명세 20.8). */
public final class SupportProgramDtos {

    private SupportProgramDtos() {
    }

    /**
     * 수집기가 한 번에 밀어 넣는 묶음.
     *
     * <p>{@code sourceSite} 는 명세 20.8 의 이름이고 컬럼은 {@code source_name} 이다. 바깥
     * 계약을 명세대로 두고 안에서 맞춘다 — 수집기는 명세를 보고 짜지 ERD 를 보지 않는다.
     */
    public record BatchRequest(
            @NotBlank @Size(max = 30) String sourceSite,
            @NotNull Instant collectedAt,
            @NotEmpty @Size(max = MAX_ITEMS) @Valid List<Item> items) {

        /**
         * 한 요청의 공고 수 상한.
         *
         * <p>기업마당 전체가 1,400건 규모라 한 번에 다 보내려 들 수 있는데, 원문까지 실으면
         * 본문이 수십 MB 가 된다. 수집기가 나눠 보내게 하고, 나눠도 {@code uk_sp_source} 로
         * upsert 라 결과가 같다.
         */
        public static final int MAX_ITEMS = 200;
    }

    /**
     * 공고 하나.
     *
     * <p>{@code rawText} 가 없을 수 있다 — 기업마당 목록 API 는 사업개요(요약)만 주고 원문은
     * 상세 페이지에 있다. 원문이 없으면 <b>구조화를 걸지 않는다</b>(요약만 보고 자격 조건을
     * 지어내게 하는 것이 가장 나쁘다). 행은 남아 나중에 원문을 채워 넣을 수 있다.
     */
    public record Item(
            @NotBlank @Size(max = 100) String externalId,
            @NotBlank @Size(max = 500) String title,
            @NotBlank @Size(max = 1000) String sourceUrl,
            @Size(max = 200) String organizer,
            String rawText,
            String rawSummary,
            Instant sourceUpdatedAt,
            LocalDate applyStart,
            LocalDate applyEnd) {
    }

    /**
     * 적재 결과.
     *
     * <p>{@code queued} 가 이 응답의 핵심이다 — <b>그 수만큼 LLM 비용이 나간다.</b> 수집기
     * 로그에 남겨 두면 "어제는 3건이었는데 오늘 1,400건"을 바로 알아챈다.
     *
     * <p>{@code needBody} 는 <b>원문이 있어야 구조화할 수 있는데 아직 없는 공고</b>다.
     * 수집기는 이 목록에 있는 것만 상세 페이지를 받아 다시 보낸다 — 이게 없으면 매번
     * 1,526건을 전부 긁어야 한다.
     */
    public record BatchResponse(int received, int created, int updated, int unchanged,
                                int queued, List<String> needBody) {
    }
}
