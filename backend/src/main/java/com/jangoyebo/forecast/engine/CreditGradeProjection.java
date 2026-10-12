package com.jangoyebo.forecast.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 잔액 예측 곡선을 자산 곡선으로 환산해 등급 구간에 대입한다 (API 16.2, P1-14).
 *
 * <p><b>순수 함수다</b> — DB·시계·네트워크를 안 건드린다. 명세의
 * {@code basis.method} 가 이 클래스가 하는 일 그대로다.
 *
 * <h2>환산이 왜 뺄셈인가</h2>
 *
 * <p>금융망 등급은 <b>이 사람의 모든 계좌</b>(수시입출금 + 예·적금) 합계로 정해지고, 우리
 * 곡선은 <b>한 사업장의 연결 계좌</b>만 그린다. 두 범위가 다르므로 곡선 값을 자산으로 그대로
 * 쓸 수 없다. 대신 <b>변화분만 얹는다</b>.
 *
 * <pre>
 *   예상 자산(d) = 현재 총자산 + (곡선(d) − 오늘 잔액)
 * </pre>
 *
 * <p>여기 깔린 가정은 <b>「예보 밖의 돈은 안 움직인다」</b>이다 — 예·적금과 연결하지 않은
 * 계좌는 그대로 있다고 본다. 사실이 아닐 수 있지만, 우리가 아는 것은 그 사업장 흐름뿐이고
 * 모르는 계좌의 변화를 지어내는 것보다 <b>가정을 적어 두는 편</b>이 낫다.
 *
 * <p>그래서 이 예측의 신뢰도는 곡선 신뢰도보다 낮다. 부르는 쪽이 한 단계 내려 표시한다.
 *
 * <h2>월별로 접는 이유</h2>
 *
 * <p>명세가 {@code {"month": "2026-10", ...}} 로 받는다. 날마다 등급을 매기면 구간 경계에서
 * 하루 단위로 오르내려 읽을 수가 없다 — <b>그 달의 마지막 예측일</b> 값을 그 달의 대표로 쓴다.
 * 등급이 언제 떨어지는지가 화면의 질문이고, 그 답은 월 단위면 충분하다.
 */
public final class CreditGradeProjection {

    private CreditGradeProjection() {
    }

    /**
     * 한 달의 추정.
     *
     * @param month          {@code YYYY-MM}
     * @param estimatedAsset 그 달 마지막 예측일의 자산
     * @param estimatedGrade 그 자산이 드는 등급 구간
     */
    public record MonthlyEstimate(String month, String estimatedGrade, BigDecimal estimatedAsset) {
    }

    /** 등급 구간 하나. {@code core.CreditAssets.GradeCriterion} 을 받아 옮겨 담는다. */
    public record Band(String grade, BigDecimal minAssetValue) {
    }

    /** 곡선의 한 점. 필요한 것은 날짜와 대표선뿐이다. */
    public record CurvePoint(LocalDate date, BigDecimal balanceExpected) {
    }

    /**
     * 월별 등급 추정.
     *
     * @param curve        오름차순 예측 곡선. 비어 있으면 결과도 빈 목록이다
     * @param todayBalance 기준일 잔액 — 곡선의 출발점. 변화분을 재는 기준이다
     * @param currentAsset 금융망이 준 현재 총자산
     * @param bands        등급 구간. 자산이 큰 등급부터 정렬돼 있어야 한다
     */
    public static List<MonthlyEstimate> project(List<CurvePoint> curve,
                                                BigDecimal todayBalance,
                                                BigDecimal currentAsset,
                                                List<Band> bands) {
        if (curve == null || curve.isEmpty() || currentAsset == null || todayBalance == null) {
            return List.of();
        }

        // 달마다 마지막 점만 남긴다. 곡선이 오름차순이라 덮어쓰면 자연히 마지막이 남는다.
        Map<YearMonth, CurvePoint> lastOfMonth = new LinkedHashMap<>();
        for (CurvePoint point : curve) {
            lastOfMonth.put(YearMonth.from(point.date()), point);
        }

        List<MonthlyEstimate> result = new ArrayList<>(lastOfMonth.size());
        for (Map.Entry<YearMonth, CurvePoint> entry : lastOfMonth.entrySet()) {
            BigDecimal asset = currentAsset.add(entry.getValue().balanceExpected().subtract(todayBalance));
            // 자산이 음수로 내려갈 수 있다(곡선이 공백을 그릴 때). 등급 판정에서는 0 으로 본다 —
            // 금융망 구간이 0 부터 시작하고, 「음수 자산」은 등급 체계에 없는 값이다.
            BigDecimal floored = asset.max(BigDecimal.ZERO);
            result.add(new MonthlyEstimate(entry.getKey().toString(), gradeOf(floored, bands), asset));
        }
        return result;
    }

    /**
     * 자산이 드는 등급.
     *
     * <p>자산이 큰 등급부터 훑어 <b>처음 넘는 구간</b>을 준다. 못 찾으면 마지막 등급이다 —
     * 금융망 구간의 최하위는 0 원부터라 항상 걸리지만, 기준이 바뀌어도 {@code null} 을
     * 돌려주지 않게 한다.
     */
    public static String gradeOf(BigDecimal asset, List<Band> bands) {
        if (bands == null || bands.isEmpty()) {
            return null;
        }
        for (Band band : bands) {
            if (asset.compareTo(band.minAssetValue()) >= 0) {
                return band.grade();
            }
        }
        return bands.get(bands.size() - 1).grade();
    }
}
