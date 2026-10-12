package com.jangoyebo.core.productsales;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 상품 집계 키 정규화 (ERD §1.9, 합의 ⑥).
 *
 * <p><b>규칙이 사는 곳은 여기 하나다.</b> UK {@code (business_id, period_start, period_end,
 * product_key)} 를 가진 쪽이 키를 만들어야 upsert 가 성립한다. 워커는 {@code productName}
 * 원문만 보내고 키를 만들지 않는다 — 같은 규칙이 두 곳에 살면 반드시 갈라지고, 갈라지는
 * 순간 같은 상품이 두 행으로 쌓인다.
 *
 * <p><b>괄호를 지우지 않는다.</b> {@code 아메리카노(HOT)} 과 {@code 아메리카노(ICE)} 는
 * 다른 상품이다. 공백만 지우고 괄호는 남긴다.
 *
 * <p>⚠ 워커의 {@code evaluate_item_normalization.py} 에도 {@code normalize_name} 이 있지만
 * 그것은 <b>매입 품목(20.5)</b> 사전 매칭용이고 규칙이 다르다(브랜드 접두·등급 토큰 제거).
 * 이름이 비슷하다고 재사용하면 {@code 아메리카노(ICE)} 같은 것이 뭉개진다.
 */
public final class ProductKeys {

    /** {@code product_key VARCHAR(255)}. 공백을 지운 뒤라 원문보다 길어지지 않지만 방어한다. */
    private static final int MAX_LENGTH = 255;

    private ProductKeys() {
    }

    /**
     * 인쇄된 상품명에서 집계 키를 만든다.
     *
     * <p>순서대로 — NFKC 정규화(전각 괄호·호환 자모·반각 가나를 표준형으로) → 모든 공백 제거
     * → 소문자 통일.
     *
     * <p>NFKC 를 먼저 하는 이유는 전각 공백({@code U+3000})·비분리 공백({@code U+00A0})이
     * 그 단계에서 보통 공백이 돼야 다음 단계가 지울 수 있기 때문이다. 순서를 바꾸면
     * POS 가 찍은 전각 공백이 키에 남는다.
     *
     * @throws BusinessException 정규화 결과가 비면 {@code VALIDATION_FAILED}. 키가 빈 문자열이면
     *                           UK 가 서로 다른 상품을 한 행으로 접는다
     */
    public static String normalize(String productName) {
        if (productName == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "상품명이 비어 있습니다.");
        }
        String key = Normalizer.normalize(productName, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "상품명이 비어 있습니다.");
        }
        return key.length() <= MAX_LENGTH ? key : key.substring(0, MAX_LENGTH);
    }
}
