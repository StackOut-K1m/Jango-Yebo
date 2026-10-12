package com.jangoyebo.core.tax;

import com.jangoyebo.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;

/**
 * 종합소득세 신고서 서식 템플릿 (P0-12 · API 14.3).
 *
 * <p><b>서식 정의가 코드가 아니라 데이터다.</b> ERD 가 「서식 개정 시 데이터만 갱신한다」로
 * 정했다 — 별지 서식은 해마다 개정되는데 항목표와 매핑표를 자바 상수로 박으면 개정 때마다
 * 재배포가 된다. 귀속연도가 키에 들어 있어 2027년 서식은 행을 하나 더 넣으면 된다.
 *
 * <p><b>사업장 공용이다.</b> {@code business_id} 가 없다 — 서식은 나라가 정한 것이라
 * 사업장마다 다르지 않다. 그래서 {@code BusinessScoped} 도 구현하지 않는다.
 */
@Entity
@Table(name = "tax_form_template", schema = "core")
public class TaxFormTemplate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code SIMPLE_LEDGER}·{@code STANDARD_RATE}·{@code SIMPLE_RATE}·{@code DOUBLE_ENTRY}. */
    @Column(name = "form_type", nullable = false)
    private String formType;

    @Column(name = "tax_year", nullable = false)
    private int taxYear;

    @Column(nullable = false)
    private String name;

    /** 근거 서식(소득세법 시행규칙 별지 서식명). 안내만 하는 유형에는 없다. */
    @Column(name = "official_form")
    private String officialForm;

    /**
     * {@code DRAFT}·{@code DRAFT_OPTIONAL}·{@code GUIDE_ONLY}·{@code CHECKLIST_ONLY}.
     *
     * <p>뒤의 둘에 초안 생성을 요청하면 {@code 400 FORM_NOT_SUPPORTED} 다(14.3).
     */
    @Column(name = "support_level", nullable = false)
    private String supportLevel;

    /** 누가 이 서식을 쓰는지. 화면이 서식을 고를 때 보여 준다. */
    @Column(name = "eligibility_note")
    private String eligibilityNote;

    /**
     * 섹션·필드 정의.
     *
     * <p>{@code [{sectionKey, label, fields:[{fieldKey, label, source, sensitive, formula, ...}]}]}
     *
     * <p>안내만 하는 유형은 <b>비어 있다</b>. 그 차이가 초안을 만들 수 있는지를 가른다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Map<String, Object>> sections = List.of();

    /**
     * 거래 카테고리(8.1) → 서식 항목 매핑 (14.3 매핑표).
     *
     * <p>{@code {카테고리코드: {fieldKey | handling, note}}}. {@code handling} 이 있으면
     * 항목 금액에 더하지 않는다는 뜻이다 — {@code EXCLUDED}(수입이 아님)·
     * {@code EXCLUDED_PERSONAL}(개인 사용)·{@code PENDING}(감가상각 검토).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "category_mapping", nullable = false)
    private Map<String, Object> categoryMapping = Map.of();

    /** AI 미전달·서버 미저장 필드 키. 조회 응답에서 값은 항상 {@code null} 이다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sensitive_fields", nullable = false)
    private List<String> sensitiveFields = List.of();

    @Column(nullable = false)
    private boolean active = true;

    protected TaxFormTemplate() {
    }

    /**
     * 초안을 만들 수 있는 서식인지 (14.3 — 안내·체크리스트 유형은 대상이 아니다).
     *
     * <p><b>지원 수준만으로는 부족하다.</b> {@code STANDARD_RATE}(기준경비율)는
     * {@code DRAFT_OPTIONAL} 인데 {@code sections} 가 비어 있어 — 칸 정의를 아직 안 넣었다 —
     * 이 검사를 통과하면 <b>항목이 0개인 초안</b>이 만들어졌다. 화면은 그것을 「집계가 0원」으로
     * 읽는다. 프론트에서 실제로 걸려 회신이 왔다(2026-09-18 · {@code S15P21C105-260}).
     *
     * <p>칸이 생기면 이 메서드는 그대로 두고 {@code sections} 만 채우면 된다 — 지원 수준과
     * 칸 정의가 따로 노는 상태를 코드가 스스로 막는다.
     */
    public boolean supportsDraft() {
        boolean levelAllows = "DRAFT".equals(supportLevel) || "DRAFT_OPTIONAL".equals(supportLevel);
        return levelAllows && sections != null && !sections.isEmpty();
    }

    public Long getId() {
        return id;
    }

    public String getFormType() {
        return formType;
    }

    public int getTaxYear() {
        return taxYear;
    }

    public String getName() {
        return name;
    }

    public String getOfficialForm() {
        return officialForm;
    }

    public String getSupportLevel() {
        return supportLevel;
    }

    public String getEligibilityNote() {
        return eligibilityNote;
    }

    public List<Map<String, Object>> getSections() {
        return sections;
    }

    public Map<String, Object> getCategoryMapping() {
        return categoryMapping;
    }

    public List<String> getSensitiveFields() {
        return sensitiveFields;
    }

    public boolean isActive() {
        return active;
    }
}
