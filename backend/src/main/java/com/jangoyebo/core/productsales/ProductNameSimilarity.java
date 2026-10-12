package com.jangoyebo.core.productsales;

import java.util.List;
import java.util.Optional;

/**
 * 표기가 비슷한 기존 상품 찾기 (API 14.4 {@code warnings}).
 *
 * <p><b>왜 필요한가.</b> 매출 인쇄물은 월 1회 입력이라 <b>중간 검토가 없다</b>. OCR 이
 * `카페라떼` 를 `카페라며` 로 읽으면 새 상품 하나가 조용히 생기고, 다음 달까지 그대로
 * 살아남아 두 상품의 구성비가 각각 절반씩 깎인다. 일 단위 입력이었다면 다음 날 눈에
 * 띄었을 오류다.
 *
 * <p><b>경고일 뿐 자동 병합이 아니다.</b> 실제로 다른 상품일 수 있다 — 판단은 사용자가
 * 하고, 합치기로 하면 14.6 이 병합한다. 도메인 규칙 5 와 같은 성격이다.
 *
 * <p>비교는 {@link ProductKeys#normalize 정규화된 키}로 한다. 공백·대소문자·전각 차이는
 * 정규화가 이미 흡수했으므로 여기 남는 것은 <b>글자가 실제로 다른 경우</b>뿐이다.
 */
public final class ProductNameSimilarity {

    /**
     * 이보다 짧으면 경고하지 않는다.
     *
     * <p>두 글자짜리 이름이 한 글자 다르면 절반이 다른 것이다 — `콜라`/`콜아` 를 오타로
     * 볼 근거가 없고, 짧은 이름은 서로 다른 상품이 원래 비슷하다.
     */
    private static final int MIN_LENGTH = 3;

    /** 이름이 길수록 오타 한두 개는 자연스럽다. 6글자마다 1 씩 늘린다. */
    private static final int LENGTH_PER_EDIT = 6;

    private ProductNameSimilarity() {
    }

    /**
     * {@code candidate} 와 가장 가까운 기존 키를 찾는다.
     *
     * @return 오타로 볼 만큼 가까운 키. 없으면 비어 있다. 완전히 같으면(= 기존 상품이면)
     *         비어 있다 — 그건 신규가 아니라 재등장이라 경고할 것이 없다
     */
    public static Optional<String> closest(String candidate, List<String> existing) {
        if (candidate == null || candidate.length() < MIN_LENGTH) {
            return Optional.empty();
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;

        for (String other : existing) {
            if (other == null || other.equals(candidate)) {
                continue;
            }
            int limit = limitFor(candidate, other);
            if (limit == 0 || Math.abs(candidate.length() - other.length()) > limit) {
                // 길이 차이만으로 이미 넘는다. 거리 계산을 아낀다.
                continue;
            }
            int distance = editDistance(candidate, other, limit);
            if (distance <= limit && distance < bestDistance) {
                best = other;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    private static int limitFor(String a, String b) {
        int shorter = Math.min(a.length(), b.length());
        return shorter < MIN_LENGTH ? 0 : Math.max(1, shorter / LENGTH_PER_EDIT);
    }

    /**
     * 레벤슈타인 거리. {@code limit} 을 넘으면 정확한 값 대신 {@code limit + 1} 을 준다.
     *
     * <p>상품명은 길어야 수십 글자라 단순 DP 로 충분하다. 조기 종료는 80행 × 기존 상품
     * 수백 개를 곱했을 때를 위한 것이다.
     */
    static int editDistance(String a, String b, int limit) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
                rowMin = Math.min(rowMin, current[j]);
            }
            if (rowMin > limit) {
                return limit + 1;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
