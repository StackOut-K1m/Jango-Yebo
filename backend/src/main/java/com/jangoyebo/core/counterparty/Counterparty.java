package com.jangoyebo.core.counterparty;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.common.entity.BaseEntity;
import com.jangoyebo.common.tenancy.BusinessScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * 사업장별 거래처 사전 — 확정된 적요↔분류 매핑 캐시 (ERD 1.5 {@code COUNTERPARTY} · API 8.6).
 *
 * <p><b>도메인 규칙 3 을 지키는 자리다.</b> "확정된 거래처↔분류 매핑은 DB에 캐시해서 재사용한다.
 * LLM 호출은 처음 보는 패턴에만." 이것이 없으면 지난달 사장님이 확정한 것과 같은 적요의 이번 달
 * 거래도 값을 다시 치른다.
 *
 * <p><b>열쇠는 (사업장, 적요, 방향) 이다.</b> {@code canonicalName} 에 거래 적요를 그대로 넣는다 —
 * 사람이 확인한 거래처명이 아직 없기 때문이다(LLM 이 추정한 이름은 아무도 검증하지 않았으므로
 * 사전의 정본으로 쓰지 않는다). 그래서 표기가 조금이라도 다르면 다른 거래처가 된다.
 * {@code aliases} 가 그것을 한 줄로 모으는 자리이고 지금은 채우지 않는다.
 */
@Entity
@Table(name = "counterparty", schema = "core")
public class Counterparty extends BaseEntity implements BusinessScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "canonical_name", nullable = false, updatable = false, length = 255)
    private String canonicalName;

    /**
     * 화면에 보이는 이름 (API 8.6 {@code displayName}).
     *
     * <p><b>매칭에 쓰지 않는다.</b> 열쇠는 {@link #canonicalName} 이고 이것은 사장님이 고치는
     * 이름이다 — 통장에는 법인명이 찍히지만 사장님은 「배달의민족」으로 부른다. 한 칸이 둘을
     * 겸하면 이름을 고치는 순간 매칭이 깨진다.
     */
    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private List<String> aliases;

    @Column(name = "default_category_id")
    private Long defaultCategoryId;

    /**
     * 열쇠의 일부다 (ERD 1.5 와 다른 점 — 거기서는 Optional).
     *
     * <p>같은 적요가 입금과 출금 양쪽에 온다. 은행은 브랜드명이 아니라 법인명을 찍으므로
     * 배달 플랫폼의 <b>정산금 입금</b>과 <b>광고비 출금</b>이 둘 다 {@code 우아한형제들} 이다.
     * 적요만 열쇠로 쓰면 한 줄이 되어 마지막 확정이 이기고, 출금 거래에 입금 카테고리가 붙는다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_flow_type", nullable = false, updatable = false, length = 20)
    private FlowType defaultFlowType;

    /** 몇 번 확정됐는가 (API {@code confirmedCount}). 사전을 믿을지 가르는 근거다. */
    @Column(name = "confirmed_count", nullable = false)
    private int confirmedCount;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(nullable = false)
    private boolean active;

    protected Counterparty() {
    }

    /** 사용자가 처음 확정한 적요. 확정 1회로 시작한다. */
    public static Counterparty firstConfirmation(Long businessId, String canonicalName,
                                                 FlowType flowType, Long categoryId) {
        Counterparty counterparty = new Counterparty();
        counterparty.businessId = businessId;
        counterparty.canonicalName = canonicalName;
        // 사람이 고치기 전까지는 적요가 곧 표시 이름이다 — 통장에서 보던 문자열이라 덜 낯설다.
        counterparty.displayName = canonicalName;
        counterparty.defaultFlowType = flowType;
        counterparty.defaultCategoryId = categoryId;
        counterparty.confirmedCount = 1;
        counterparty.lastUsedAt = Instant.now();
        counterparty.active = true;
        return counterparty;
    }

    /**
     * LLM 이 같은 판단을 반복하지 않도록 담아 둔다 (AI 담당 요청 2026-09-14).
     *
     * <p><b>확정 횟수가 0 이다.</b> 사람이 본 적 없는 매핑이라는 뜻이고, 이 사전으로 분류한
     * 거래는 <b>확인 질문에 남는다</b>. 호출은 아끼되 모델의 오류가 굳지는 않게 하는 지점이다 —
     * 그대로 자동 확정하면 틀린 판단이 다음 거래에 그대로 적용되고 그 결과가 또 쌓인다.
     *
     * <p>사장님이 그 확인 질문에 답하면 {@link #confirmAgain} 이 불려 확정 1회가 되고,
     * 그때부터 묻지 않는다.
     */
    public static Counterparty fromSuggestion(Long businessId, String canonicalName,
                                              FlowType flowType, Long categoryId) {
        Counterparty counterparty = new Counterparty();
        counterparty.businessId = businessId;
        counterparty.canonicalName = canonicalName;
        counterparty.displayName = canonicalName;
        counterparty.defaultFlowType = flowType;
        counterparty.defaultCategoryId = categoryId;
        counterparty.confirmedCount = 0;
        counterparty.lastUsedAt = Instant.now();
        counterparty.active = true;
        return counterparty;
    }

    /**
     * 모델이 같은 적요를 또 판단했다.
     *
     * <p><b>사람이 확정한 항목은 건드리지 않는다</b>(요청 4번 — USER 가 LLM 보다 우선).
     * 사장님이 임차료로 고쳐 둔 것을 모델이 다시 광고비로 되돌리면, 고친 것이 무슨 소용인가.
     *
     * @return 반영했으면 {@code true}
     */
    public boolean suggestAgain(Long categoryId) {
        if (confirmedCount > 0) {
            return false;
        }
        this.defaultCategoryId = categoryId;
        this.lastUsedAt = Instant.now();
        this.active = true;
        return true;
    }

    /** 사람이 확정한 항목인가. 아니면 모델이 담아 둔 것이고 확인 질문이 남아 있다. */
    public boolean isUserConfirmed() {
        return confirmedCount > 0;
    }

    /**
     * 같은 적요를 또 확정했다.
     *
     * <p><b>마지막 확정이 이긴다.</b> 사장님이 분류를 바꿨다면 그것이 지금의 판단이고, 사전은
     * 다음 거래에 그 판단을 적용해야 한다. 확정 횟수는 <b>바뀌어도 줄이지 않는다</b> — 이 값은
     * "이 분류가 몇 번 맞았나"가 아니라 "이 적요를 몇 번 만졌나"이고, 사전을 자동 분류에 쓸지
     * 가르는 데 쓴다.
     */
    public void confirmAgain(Long categoryId) {
        // 방향은 바꾸지 않는다 — 열쇠의 일부라, 방향이 다르면 애초에 다른 행이다.
        this.defaultCategoryId = categoryId;
        this.confirmedCount++;
        this.lastUsedAt = Instant.now();
        // 사용자가 다시 확정한 거래처를 비활성으로 둘 이유가 없다.
        this.active = true;
    }

    /**
     * 사장님이 사전을 고쳤다 (API 8.6 {@code PATCH}).
     *
     * <p><b>매칭 열쇠는 건드리지 않는다.</b> 이름을 바꿔도 같은 적요가 계속 이 줄을 찾아야 한다.
     *
     * <p>분류를 바꿔도 <b>이미 붙은 거래는 그대로 둔다</b> — 명세가 "수정하면 <b>이후</b> 동일
     * 적요 거래는 사전 단계에서 자동 분류된다" 로 정했다. 지난 분류까지 소급해 바꾸면 사장님이
     * 확인해 둔 것이 말없이 뒤집힌다.
     *
     * <p>사람이 고친 것이므로 확정으로 센다 — 모델이 담아 둔 줄을 고쳤다면 그 순간
     * 사용자 확정이 되고, 다음부터 확인을 묻지 않는다.
     */
    public void updateByUser(String displayName, Long defaultCategoryId) {
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName.strip();
        }
        if (defaultCategoryId != null) {
            this.defaultCategoryId = defaultCategoryId;
        }
        this.confirmedCount++;
        this.active = true;
    }

    public Long getId() { return id; }
    @Override public Long getBusinessId() { return businessId; }
    public String getCanonicalName() { return canonicalName; }
    public String getDisplayName() { return displayName; }
    public List<String> getAliases() { return aliases; }
    public Long getDefaultCategoryId() { return defaultCategoryId; }
    public FlowType getDefaultFlowType() { return defaultFlowType; }
    public int getConfirmedCount() { return confirmedCount; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public boolean isActive() { return active; }
}
