package com.jangoyebo.core.support;

import com.jangoyebo.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 지원사업 공고 (ERD {@code SUPPORT_PROGRAM}, API 17.1~17.3, P1-15).
 *
 * <p><b>사업장 소유가 아니다.</b> {@code businessId} 가 없는 이유가 이것이다 — 공고는 모든
 * 사장님에게 같은 내용이고, 사업장마다 복제하면 수집과 LLM 구조화 비용이 사업장 수만큼
 * 늘어난다. 맞춤 추천(17.3)은 이 표를 읽어 요청 시 계산한다.
 *
 * <p><b>두 단계로 채워진다.</b> 수집(20.8)이 원문·제목·링크를 넣고, 구조화(20.6)가
 * {@link #eligibility} 와 금액·기간·종류를 채운다. 그래서 구조화 관련 필드가 전부 nullable 이다 —
 * 「아직 안 읽었다」와 「읽었는데 없더라」를 구분하는 것은 {@link #isStructured()} 다.
 *
 * <p><b>구조화에 실패해도 행은 남긴다.</b> 원문과 링크가 있으면 사람이 원문으로 갈 수 있고,
 * 프롬프트를 고친 뒤 다시 돌릴 수 있다. 검색에서만 빠진다.
 */
@Entity
@Table(name = "support_program", schema = "core")
public class SupportProgram extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 수집 출처. {@code BIZINFO} 로 시작하고 {@code K_STARTUP}·{@code SMES} 는 확장 단계다. */
    @Column(name = "source_name", nullable = false, updatable = false, length = 30)
    private String sourceName;

    /** 출처가 매긴 공고 ID. 기업마당은 {@code PBLN_000000000112345} 꼴이다. */
    @Column(name = "external_id", nullable = false, updatable = false, length = 100)
    private String externalId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(length = 200)
    private String organizer;

    @Column(name = "source_url", nullable = false, length = 1000)
    private String sourceUrl;

    /** {@code FUNDING}·{@code SUBSIDY}·{@code VOUCHER}·{@code CONSULTING}·{@code OTHER} (17.1). */
    @Column(name = "support_type", length = 20)
    private String supportType;

    @Column(name = "max_amount", precision = 19, scale = 4)
    private BigDecimal maxAmount;

    @Column(name = "interest_rate", precision = 6, scale = 4)
    private BigDecimal interestRate;

    /**
     * 신청 기간. <b>둘 다 없을 수 있다</b> — 상시모집 공고가 그렇고, 기업마당도
     * {@code reqstDt} 를 선택 항목으로 둔다. 없다고 거르면 정상 공고가 사라진다.
     */
    @Column(name = "application_start_date")
    private LocalDate applicationStartDate;

    @Column(name = "application_end_date")
    private LocalDate applicationEndDate;

    /** 예상 집행 소요일. 공백 연계 추천(17.3)이 「언제 들어오나」를 역산하는 값이다. */
    @Column(name = "expected_disbursement_days")
    private Integer expectedDisbursementDays;

    /**
     * 지역·업종·매출 상한·업력·제외 대상·불확실 조건·필수 서류 (17.2).
     *
     * <p><b>컬럼으로 펴지 않는다.</b> 조건의 모양이 공고마다 다르고 계속 늘어난다 —
     * {@code EvidenceExtraction.headerData} 와 같은 이유다. 검색에 쓰는 것만 일반 컬럼으로 뺐다.
     *
     * <p>비어 있으면 아직 구조화되지 않았거나 실패한 것이다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> eligibility = Map.of();

    /** 공고 원문. LLM 구조화(20.6) 입력이고, 프롬프트를 고쳐 다시 돌릴 때도 이것을 쓴다. */
    @Column(name = "raw_text")
    private String rawText;

    /**
     * 사업개요 한두 문장 (17.2 {@code rawSummary}).
     *
     * <p><b>모델이 만들지 않는다.</b> 기업마당 목록 API 가 {@code bsnsSumryCn} 으로 그대로
     * 실어 주므로 수집기가 받아 적는다. {@link #rawText} 와 역할이 다르다 — 저쪽은 LLM 이
     * 읽을 원문 전문이고 이쪽은 사람이 읽을 요약이라, 서로를 대신하지 않는다.
     */
    @Column(name = "raw_summary")
    private String rawSummary;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    /**
     * 출처가 공고를 마지막으로 고친 시각 (기업마당 {@code updtPnttm}, V25).
     *
     * <p>{@link #collectedAt} 과 다르다 — 저쪽은 <b>우리가 받아 온 시각</b>이라 매번 바뀌고
     * 이쪽은 <b>출처가 고친 시각</b>이라 안 바뀌는 것이 정상이다. 변경 감지에 쓸 수 있는
     * 것은 이쪽뿐이고, 이게 없으면 매일 1,526건의 상세 페이지를 전부 받아야 한다.
     */
    @Column(name = "source_updated_at")
    private Instant sourceUpdatedAt;

    /** {@code OPEN}·{@code CLOSED}·{@code UPCOMING}·{@code UNKNOWN}. CHECK 이 정본이다. */
    @Column(nullable = false, length = 20)
    private String status = "UNKNOWN";

    protected SupportProgram() {
    }

    private SupportProgram(String sourceName, String externalId, String title, String sourceUrl,
                           String organizer, String rawText, String rawSummary,
                           Instant sourceUpdatedAt, Instant collectedAt) {
        this.sourceName = sourceName;
        this.externalId = externalId;
        this.title = title;
        this.sourceUrl = sourceUrl;
        this.organizer = organizer;
        this.rawText = rawText;
        this.rawSummary = rawSummary;
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.collectedAt = collectedAt;
    }

    /**
     * 수집기가 막 받아온 공고 (20.8).
     *
     * <p>구조화 전이라 자격·금액·기간이 비어 있다. {@code status} 는 {@code UNKNOWN} 이다 —
     * 모집 상태는 신청 기간을 읽어야 알 수 있고, 그건 구조화의 결과다.
     */
    public static SupportProgram collected(String sourceName, String externalId, String title,
                                           String sourceUrl, String organizer, String rawText,
                                           String rawSummary, Instant sourceUpdatedAt,
                                           Instant collectedAt) {
        return new SupportProgram(sourceName, externalId, title, sourceUrl, organizer, rawText,
                rawSummary, sourceUpdatedAt, collectedAt);
    }

    /**
     * 재수집분을 덮어쓴다. <b>원문이 실제로 바뀌었으면 {@code true} 를 돌려준다.</b>
     *
     * <p>매일 전량을 받아 upsert 하므로 대부분은 내용이 같다. 바뀌지 않은 공고까지 다시
     * 구조화하면 <b>매일 1,400건어치 LLM 비용이 나간다.</b> 호출하는 쪽은 이 반환값이
     * {@code true} 일 때만 구조화 Job 을 건다.
     *
     * <p>제목·링크·기관은 원문이 그대로여도 정정될 수 있어 항상 반영한다.
     */
    public boolean refreshFrom(String title, String sourceUrl, String organizer, String rawText,
                               String rawSummary, Instant sourceUpdatedAt, Instant collectedAt) {
        // ⚠ **null 은 「본문이 없다」가 아니라 「이번에 안 보냈다」다.** 수집기는 목록만 먼저
        // 보내고(1단계) 필요한 것만 본문을 채워 다시 보낸다(2단계). 여기서 null 로 덮으면
        // 1단계가 이미 받아 둔 원문을 지워 매번 다시 긁게 된다.
        boolean rawChanged = rawText != null && !Objects.equals(this.rawText, rawText);
        boolean sourceChanged =
                sourceUpdatedAt != null && !Objects.equals(this.sourceUpdatedAt, sourceUpdatedAt);

        this.title = title;
        this.sourceUrl = sourceUrl;
        this.organizer = organizer;
        if (rawText != null) {
            this.rawText = rawText;
        }
        if (rawSummary != null) {
            this.rawSummary = rawSummary;
        }
        if (sourceUpdatedAt != null) {
            this.sourceUpdatedAt = sourceUpdatedAt;
        }
        this.collectedAt = collectedAt;
        return rawChanged || sourceChanged;
    }

    /**
     * 출처가 적어 둔 신청 기간을 반영한다.
     *
     * <p><b>출처의 값이 모델 추출보다 낫다.</b> 기업마당은 {@code reqstBeginEndDe} 로 기간을
     * 직접 준다 — 그걸 두고 원문에서 다시 뽑게 하면 틀릴 자리를 하나 더 만드는 셈이다.
     * 그래서 구조화는 이 값을 덮지 않는다({@link #applyStructuring}).
     */
    public void applyPeriod(LocalDate start, LocalDate end) {
        if (start == null && end == null) {
            return;
        }
        this.applicationStartDate = start;
        this.applicationEndDate = end;
        this.status = recruitmentStatus();
    }

    /**
     * 구조화 결과를 반영한다 (20.6 콜백).
     *
     * <p>{@code eligibility} 는 워커가 준 것을 그대로 담는다 — 여기서 자격을 <b>판정하지
     * 않는다</b>. 판정은 Java 규칙 엔진이 17.3 에서 한다(기술스택 6.6).
     *
     * <p><b>출처가 준 기간은 덮지 않는다.</b> 수집기가 {@code reqstBeginEndDe} 를 이미 넣어
     * 뒀으면 그게 정본이고, 모델 값은 비어 있을 때만 쓴다.
     */
    public void applyStructuring(String supportType, BigDecimal maxAmount, BigDecimal interestRate,
                                 LocalDate applicationStartDate, LocalDate applicationEndDate,
                                 Integer expectedDisbursementDays, Map<String, Object> eligibility) {
        this.supportType = supportType;
        this.maxAmount = maxAmount;
        this.interestRate = interestRate;
        if (this.applicationStartDate == null) {
            this.applicationStartDate = applicationStartDate;
        }
        if (this.applicationEndDate == null) {
            this.applicationEndDate = applicationEndDate;
        }
        this.expectedDisbursementDays = expectedDisbursementDays;
        // ⚠ Map.copyOf 를 쓰지 않는다 — **값이 null 이면 NPE 를 던진다.** 워커의
        // Eligibility 는 maxAnnualRevenue·minYearsInBusiness 를 모르면 null 로 보내므로
        // 실제 콜백은 거의 항상 null 을 하나는 담고 있다. 「모른다」를 그대로 보존해야
        // 「조건이 없다」와 구분된다.
        this.eligibility = eligibility == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(eligibility));
        this.status = recruitmentStatus();
    }

    /**
     * 모집 상태를 신청 기간으로 정한다.
     *
     * <p><b>워커에게 시키지 않는다.</b> 「오늘」이 모델의 시점에 달리면 어제 만든 결과가
     * 오늘 틀린다(도메인 규칙 2 — 수치·날짜 계산은 백엔드).
     *
     * <p>기간을 모르면 {@code UNKNOWN} 이다. 상시모집이라 비었을 수도, 구조화가 못 읽었을
     * 수도 있는데 <b>둘 다 「열려 있다」고 단정할 근거가 아니다.</b>
     */
    private String recruitmentStatus() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        if (applicationEndDate != null && applicationEndDate.isBefore(today)) {
            return "CLOSED";
        }
        if (applicationStartDate != null && applicationStartDate.isAfter(today)) {
            return "UPCOMING";
        }
        if (applicationStartDate == null && applicationEndDate == null) {
            return "UNKNOWN";
        }
        return "OPEN";
    }

    /**
     * 모집 상태를 오늘 기준으로 다시 맞춘다. 바뀌었으면 {@code true}.
     *
     * <p><b>{@code status} 는 쓰는 순간의 스냅샷이다.</b> 수집({@link #applyPeriod})과
     * 구조화({@link #applyStructuring})가 계산해 두고 나면 날짜가 지나도 저절로 바뀌지
     * 않는다 — 마감돼 출처 목록에서 빠진 공고는 다시 수집되지도 않는다. 그래서
     * {@link SupportProgramStatusScheduler} 가 매일 이걸 부른다.
     *
     * <p>판정은 {@link #recruitmentStatus()} 하나에 둔다. SQL 로 옮겨 쓰면 같은 규칙이 두
     * 벌이 되고, 둘이 갈라지는 날 화면과 목록이 서로 다른 말을 한다.
     */
    public boolean refreshStatus() {
        String next = recruitmentStatus();
        if (next.equals(status)) {
            return false;
        }
        this.status = next;
        return true;
    }

    /** 원문을 이미 갖고 있나. 없으면 수집기가 상세 페이지를 받아 와야 한다. */
    public boolean hasRawText() {
        return rawText != null && !rawText.isBlank();
    }

    /**
     * 구조화가 끝났나.
     *
     * <p>검색(17.1) 대상은 이것이 {@code true} 인 공고뿐이다 — 자격 조건을 모르는 공고를
     * 「받을 수 있는 지원금」으로 내보내면 안 된다.
     */
    public boolean isStructured() {
        return eligibility != null && !eligibility.isEmpty();
    }

    /**
     * 조건이 불명확해 기관 확인이 필요한가 (17.1 {@code needsVerification}).
     *
     * <p>구조화가 확신하지 못한 조건은 {@code uncertainConditions} 에 남는다 — 임의로 채우지
     * 않는 것이 이 기능의 핵심 안전장치다(기술스택 6.6). 하나라도 있으면 화면에 표시한다.
     */
    public boolean needsVerification() {
        Object uncertain = eligibility == null ? null : eligibility.get("uncertainConditions");
        return uncertain instanceof java.util.Collection<?> values && !values.isEmpty();
    }

    public Long getId() {
        return id;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getTitle() {
        return title;
    }

    public String getOrganizer() {
        return organizer;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getSupportType() {
        return supportType;
    }

    public BigDecimal getMaxAmount() {
        return maxAmount;
    }

    public BigDecimal getInterestRate() {
        return interestRate;
    }

    public LocalDate getApplicationStartDate() {
        return applicationStartDate;
    }

    public LocalDate getApplicationEndDate() {
        return applicationEndDate;
    }

    public Integer getExpectedDisbursementDays() {
        return expectedDisbursementDays;
    }

    public Map<String, Object> getEligibility() {
        return eligibility == null ? Map.of() : Collections.unmodifiableMap(eligibility);
    }

    public String getRawText() {
        return rawText;
    }

    public String getRawSummary() {
        return rawSummary;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }

    public Instant getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }

    public String getStatus() {
        return status;
    }
}
