package com.jangoyebo.core.productsales;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 표기가 비슷한 기존 상품 찾기 (API 14.4 {@code warnings}).
 *
 * <p><b>오탐이 비싼 쪽이 아니다 — 미탐이 비싸다.</b> 월 1회 입력이라 중간 검토가 없어서,
 * 오인식 하나가 다음 달까지 살아남으며 두 상품의 구성비를 각각 깎는다. 그렇다고 실제로
 * 다른 상품을 묶어 경고하면 사장님이 경고를 안 보게 되므로, 아래 경계가 그 둘 사이다.
 *
 * <p>Docker 가 필요 없다.
 */
class ProductNameSimilarityTests {

    private static final List<String> KNOWN =
            List.of("카페라떼", "카페모카", "아메리카노", "아메리카노(ice)", "콜라");

    /** 이것 하나 잡으려고 만든 기능이다 — 명세 14.4 의 예시가 그대로 여기 있다. */
    @Test
    void catchesASingleCharacterMisread() {
        assertThat(ProductNameSimilarity.closest("카페라며", KNOWN)).contains("카페라떼");
    }

    /**
     * <b>괄호 변형은 다른 상품이다.</b> {@code 아메리카노} 와 {@code 아메리카노(ICE)} 를 묶어
     * 경고하면 여름·겨울 구성비가 뭉개진다.
     */
    @Test
    void doesNotFlagParenthesisedVariantsAsTypos() {
        assertThat(ProductNameSimilarity.closest("아메리카노(hot)", KNOWN)).isEmpty();
    }

    /** 두 글자 다르면 다른 상품으로 본다. 라떼와 모카는 실제로 다른 메뉴다. */
    @Test
    void doesNotFlagGenuinelyDifferentProducts() {
        assertThat(ProductNameSimilarity.closest("카페모카", List.of("카페라떼"))).isEmpty();
    }

    /**
     * 짧은 이름은 경고하지 않는다. 두 글자에서 한 글자가 다르면 절반이 다른 것이라
     * 오타로 볼 근거가 없다.
     */
    @Test
    void staysQuietForVeryShortNames() {
        assertThat(ProductNameSimilarity.closest("콜아", KNOWN)).isEmpty();
        assertThat(ProductNameSimilarity.closest("라떼", List.of("라면"))).isEmpty();
    }

    /** 이미 있는 상품은 신규가 아니라 재등장이다 — 경고할 것이 없다. */
    @Test
    void saysNothingWhenTheNameAlreadyExists() {
        assertThat(ProductNameSimilarity.closest("카페라떼", KNOWN)).isEmpty();
    }

    /** 이름이 길면 오타 두 개까지 봐준다. */
    @Test
    void allowsMoreEditsForLongerNames() {
        assertThat(ProductNameSimilarity.closest("서울우유흰우유1리더", List.of("서울우유흰우유1리터")))
                .contains("서울우유흰우유1리터");
    }

    /** 후보가 여럿이면 가장 가까운 것을 고른다. */
    @Test
    void picksTheClosestCandidate() {
        assertThat(ProductNameSimilarity.closest("카페라뗴", List.of("카페라떼", "카페라뗴뗴")))
                .contains("카페라떼");
    }

    @Test
    void handlesAnEmptyHistory() {
        assertThat(ProductNameSimilarity.closest("카페라떼", List.of())).isEmpty();
    }

    /** 조기 종료가 정확도를 바꾸면 안 된다 — 한계 안의 거리는 정확해야 한다. */
    @Test
    void earlyExitDoesNotChangeDistancesWithinTheLimit() {
        assertThat(ProductNameSimilarity.editDistance("카페라떼", "카페라며", 9)).isEqualTo(1);
        assertThat(ProductNameSimilarity.editDistance("카페라떼", "카페모카", 9)).isEqualTo(2);
        assertThat(ProductNameSimilarity.editDistance("가나다", "라마바", 1))
                .as("한계를 넘으면 limit+1 로 끊는다").isEqualTo(2);
    }
}
