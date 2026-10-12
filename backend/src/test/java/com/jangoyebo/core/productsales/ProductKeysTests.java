package com.jangoyebo.core.productsales;

import com.jangoyebo.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 상품 집계 키 정규화 (합의 ⑥).
 *
 * <p><b>이 규칙이 흔들리면 같은 상품이 두 행으로 쌓인다.</b> UK 가
 * {@code (business_id, period_start, period_end, product_key)} 라 키가 갈리는 순간 upsert 가
 * insert 가 되고, 구성비 분모가 두 번 세어진다. 그래서 값 하나하나가 회귀선이다.
 *
 * <p>Docker 가 필요 없다 — 순수 함수다.
 */
class ProductKeysTests {

    /** 공백은 어디에 있든 지운다. POS 가 열을 맞추려고 넣는 공백이 상품마다 다르다. */
    @Test
    void removesEveryKindOfWhitespace() {
        assertThat(ProductKeys.normalize("카페 라떼")).isEqualTo("카페라떼");
        assertThat(ProductKeys.normalize("  카페라떼  ")).isEqualTo("카페라떼");
        assertThat(ProductKeys.normalize("카페\t라떼")).isEqualTo("카페라떼");
    }

    /**
     * <b>NFKC 가 공백 제거보다 먼저여야 한다.</b> 전각 공백({@code U+3000})·비분리 공백
     * ({@code U+00A0})은 NFKC 를 거쳐야 보통 공백이 되고, 그래야 다음 단계가 지운다.
     * 순서를 바꾸면 POS 가 찍은 전각 공백이 키에 남아 같은 상품이 두 행이 된다.
     */
    @Test
    void normalizesBeforeStrippingSoWideSpacesDisappear() {
        assertThat(ProductKeys.normalize("카페　라떼")).isEqualTo("카페라떼");
        assertThat(ProductKeys.normalize("카페 라떼")).isEqualTo("카페라떼");
    }

    /** 전각 영숫자·괄호도 NFKC 가 반각으로 내린다. 같은 상품이 폰트 때문에 갈리면 안 된다. */
    @Test
    void foldsFullWidthFormsToAscii() {
        assertThat(ProductKeys.normalize("ＡＭＥＲＩＣＡＮＯ")).isEqualTo("americano");
        assertThat(ProductKeys.normalize("아메리카노（ＨＯＴ）")).isEqualTo("아메리카노(hot)");
    }

    /**
     * <b>괄호를 지우지 않는다.</b> {@code 아메리카노(HOT)} 과 {@code (ICE)} 는 다른 상품이고,
     * 합치면 여름·겨울 구성비가 통째로 뭉개진다.
     */
    @Test
    void keepsParenthesesSoHotAndIceStayApart() {
        assertThat(ProductKeys.normalize("아메리카노(HOT)"))
                .isNotEqualTo(ProductKeys.normalize("아메리카노(ICE)"));
        assertThat(ProductKeys.normalize("아메리카노(HOT)")).isEqualTo("아메리카노(hot)");
    }

    /** 대소문자만 다른 표기는 같은 키다. */
    @Test
    void unifiesCase() {
        assertThat(ProductKeys.normalize("Cafe Latte"))
                .isEqualTo(ProductKeys.normalize("CAFE LATTE"));
    }

    /**
     * 빈 키는 막는다. 허용하면 UK 가 <b>서로 다른 상품을 한 행으로 접는다</b> —
     * 상품명을 못 읽은 행이 전부 같은 키가 된다.
     */
    @Test
    void rejectsNamesThatNormalizeToNothing() {
        assertThatThrownBy(() -> ProductKeys.normalize("   "))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ProductKeys.normalize(null))
                .isInstanceOf(BusinessException.class);
    }

    /** {@code product_key VARCHAR(255)} 를 넘기지 않는다. */
    @Test
    void truncatesToColumnLength() {
        assertThat(ProductKeys.normalize("가".repeat(300))).hasSize(255);
    }
}
