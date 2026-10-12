package com.jangoyebo.core.support;

import com.jangoyebo.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * 서류 발급처 사전 한 줄 (P1-15 · API 17.4).
 *
 * <p><b>사업장 공용 기준 데이터다.</b> {@code business_id} 가 없다 — 사업자등록증명을 어디서
 * 떼는지는 사장님마다 다르지 않다. 그래서 {@code BusinessScoped} 도 구현하지 않는다.
 *
 * <p><b>{@code match_keys} 를 매핑하지 않는다.</b> 그 컬럼은 {@code TEXT[]} 이고 이 저장소에
 * Postgres 배열을 매핑한 전례가 없다. 읽는 쪽은 전부 네이티브 쿼리
 * ({@link DocumentGuideRepository})라 엔티티가 들고 있을 이유가 없고, 쓰는 쪽은 시드뿐이다.
 * 타입 매핑을 하나 새로 들이는 값이 그 편익보다 크다.
 *
 * <p><b>{@code issueUrl} 이 비는 행이 있다.</b> 통장 사본·임대차계약서는 발급받는 서류가
 * 아니다 — 사전에서 빼면 17.2 가 이름만 보여 주고 사장님이 없는 발급처를 찾아다닌다.
 * 링크 없이 {@code method = "직접 준비"} 로 담는 편이 「발급처가 없다」를 말해 준다.
 */
@Entity
@Table(name = "document_guide", schema = "core")
public class DocumentGuide extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 서류명. UK — 사전의 표제어다. */
    @Column(name = "document_name", nullable = false)
    private String documentName;

    /** 발급처(홈택스·정부24 등). 발급이 아닌 서류는 보관 주체를 적는다(「본인 보관」). */
    @Column(nullable = false)
    private String issuer;

    /**
     * 발급 링크. <b>기관 대표 주소를 쓰고 딥링크를 쓰지 않는다</b> — 정부 사이트의 발급 화면
     * 주소는 개편 때마다 바뀌는데, 죽은 링크를 만나는 시점이 하필 서류를 준비하는 중이다.
     * 찾아가는 경로는 {@link #notes} 에 적는다.
     */
    @Column(name = "issue_url")
    private String issueUrl;

    /** 발급 방법(「온라인 즉시 발급」·「직접 준비」 등). */
    @Column(nullable = false)
    private String method;

    /** 수수료. 무료는 {@code 0} 이고 {@code null} 이 아니다 — 「모름」과 「무료」는 다르다. */
    @Column(nullable = false)
    private BigDecimal fee;

    /** 소요 시간. 「즉시」·「1~3일」. */
    @Column(name = "estimated_time")
    private String estimatedTime;

    /** 준비물·주의사항. 발급 경로도 여기 적는다. */
    @Column
    private String notes;

    /** 사전에서 내릴 때 쓴다. 지우지 않는 이유는 지난 공고가 그 서류를 가리키고 있어서다. */
    @Column(nullable = false)
    private boolean active = true;

    protected DocumentGuide() {
    }

    public Long getId() {
        return id;
    }

    public String getDocumentName() {
        return documentName;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getIssueUrl() {
        return issueUrl;
    }

    public String getMethod() {
        return method;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public String getEstimatedTime() {
        return estimatedTime;
    }

    public String getNotes() {
        return notes;
    }

    public boolean isActive() {
        return active;
    }
}
