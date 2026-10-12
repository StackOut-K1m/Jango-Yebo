package com.jangoyebo.core.transaction;

/**
 * 종합소득세 검토 상태 (API 8.7).
 *
 * <p>계좌에서 출금됐다는 사실만으로 필요경비 인정 여부가 정해지지 않으므로 거래별로 따로 관리한다.
 * P0-12 내보내기의 검토 상태 열이 이 값을 원천으로 쓴다.
 *
 * <p><b>{@code isExcluded} 와 독립이다.</b> 장비 구입은 예측에서 빼되 경비로 인정될 수 있고,
 * 개인 사용 지출은 경비에서 빼되 예측에는 반영해야 한다(API 8.2).
 *
 * <p><b>서버가 자동으로 부여하는 값은 둘뿐이다</b> — 증빙 연결 여부로 기계적으로 알 수 있는
 * {@link #EVIDENCE_CONFIRMED}·{@link #EVIDENCE_MISSING}. 나머지는 사용자만 지정하며,
 * 사용자가 지정한 값은 자동 판정이 덮어쓰지 않는다(SRS 1.3 "세무 판단의 자동 확정 방지").
 *
 * <p>값 집합은 {@code ck_tx_tax_review} CHECK 과 일치해야 한다. 순서를 바꿔도 무방하지만
 * 이름은 DB 에 문자열로 저장되므로 바꾸면 기존 행을 읽지 못한다.
 */
public enum TaxReviewStatus {

    /** 기본값. */
    UNREVIEWED("미검토"),

    /** 자동 — 증빙이 연결됨. */
    EVIDENCE_CONFIRMED("증빙 확인 완료"),

    /** 자동 — 증빙이 연결되지 않음. */
    EVIDENCE_MISSING("증빙 누락"),

    /** 사용자 — 사업 관련성 확인 필요. */
    BUSINESS_USE_UNCLEAR("사업 관련성 확인 필요"),

    /** 사용자 — 개인 사용 가능성. */
    PERSONAL_SUSPECTED("개인 사용 가능성 있음"),

    /** 사용자 — 중복 등록 가능성. 자동 탐지는 P1. */
    DUPLICATE_SUSPECTED("중복 등록 가능성 있음"),

    /** 사용자 — 세무전문가 확인 필요. */
    EXPERT_REVIEW("세무전문가 확인 필요"),

    /**
     * 자산 등록·감가상각 검토 필요.
     *
     * <p><b>값만 예약돼 있다(화면 P2).</b> 자산대장 관리가 상용화 확장 범위라 지금은 아무도
     * 부여하지 않는다. DB CHECK 에 미리 넣어 둔 덕에 나중에 마이그레이션 없이 화면만 붙인다.
     */
    ASSET_REVIEW("자산 등록 또는 감가상각 검토 필요");

    private final String label;

    TaxReviewStatus(String label) {
        this.label = label;
    }

    /**
     * 사람이 읽는 이름.
     *
     * <p>준비자료 패키지의 <b>검토 상태 열</b>이 이 값을 쓴다(SRS §P0-12 —
     * {@code 07_거래증빙_연결목록.xlsx}). 문구는 SRS 가 정한 그대로다 — 세무대리인이
     * 받아 보는 문서라 「개인 사용」처럼 줄이면 <b>확정된 판단</b>으로 읽힌다.
     *
     * <p>모르는 값이 올 자리는 없다 — DB CHECK 과 이 enum 이 같은 집합이다.
     */
    public String label() {
        return label;
    }

    /** 저장된 문자열을 라벨로. 모르는 값은 그대로 돌려준다 — 시트에 빈칸을 남기지 않는다. */
    public static String labelOf(String name) {
        if (name == null) {
            return "";
        }
        try {
            return valueOf(name).label();
        } catch (IllegalArgumentException e) {
            return name;
        }
    }
}
