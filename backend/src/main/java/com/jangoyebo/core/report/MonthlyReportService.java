package com.jangoyebo.core.report;

import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.core.productsales.ProductSalesDtos.ShareItem;
import com.jangoyebo.core.productsales.ProductSalesDtos.ShareResponse;
import com.jangoyebo.core.productsales.ProductSalesService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 월별 지출·수익 리포트 (API 15.3 · P1-19).
 *
 * <p><b>저장하지 않는다.</b> 아직 마감되지 않은 당월을 물으면 「지금까지의 집계」를 줘야 하고
 * (`isPartial`), 그 값은 매일 달라진다. 스냅샷으로 두면 어제 숫자를 오늘 것처럼 내보낸다.
 * {@code REPORT} 테이블은 브리핑(15.2)의 것이고 이 리포트는 그 <b>근거</b>로 읽힌다.
 *
 * <p><b>집계 대상은 확정 분류뿐이다.</b> 제안 상태의 분류가 틀려 있으면 카테고리 비중이
 * 엉뚱하게 잡히고, 그 표가 곧 「무엇에 돈을 썼나」의 답이 된다.
 *
 * <h2>명세에 정의가 없어 내지 않는 것</h2>
 *
 * <p><b>{@code expense.nextMonthEstimate} 는 키가 없다.</b> 명세가 `{min, max}` 모양만 적고
 * <b>계산 방법을 정하지 않았다.</b> 예측 곡선은 30일 <i>잔액</i>이라 월별 경비 추정이 아니고,
 * 최근 몇 달 평균으로 임의로 정의하면 그것이 화면에 「다음 달 경비 전망」으로 나간다 —
 * 돈에 대한 추정을 팀 합의 없이 만들지 않는다(도메인 규칙 1).
 *
 * <p><b>{@code highlights} 는 카테고리 단위까지만 짚는다.</b> 명세 예시의
 * 「우유 단가 상승이 주요 원인입니다」와 {@code sourceRef.itemId} 는 품목 가계부(13.6)가
 * 있어야 하고 그것은 아직 없다. 문구는 <b>템플릿</b>이다 — LLM 을 부르지 않으므로 비용이
 * 없고, 수치를 문장에 옮기는 일에 모델이 필요하지도 않다.
 */
@Service
public class MonthlyReportService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 매출로 보는 입금 분류. 대출 실행·대표자 자금 투입은 매출이 아니다(14.2 와 같은 집합). */
    private static final List<String> SALES_CHANNELS =
            List.of("CARD_SETTLEMENT", "PLATFORM_SETTLEMENT", "CASH_SALES", "TRANSFER_SALES");

    /** 이 정도는 움직였다고 말할 만한 선. 작은 금액의 큰 비율이 목록을 채우지 않게 한다. */
    private static final BigDecimal HIGHLIGHT_MIN_AMOUNT = new BigDecimal("300000");
    private static final BigDecimal HIGHLIGHT_MIN_RATE = new BigDecimal("0.2");
    private static final int HIGHLIGHT_MAX = 3;

    private final JdbcTemplate jdbc;
    private final ProductSalesService productSales;
    private final BusinessGuard guard;
    private final Clock clock;

    MonthlyReportService(JdbcTemplate jdbc, ProductSalesService productSales,
                         BusinessGuard guard, Clock clock) {
        this.jdbc = jdbc;
        this.productSales = productSales;
        this.guard = guard;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> of(Long businessId, YearMonth month) {
        guard.assertOwnsBusiness(businessId);

        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        YearMonth previous = month.minusMonths(1);
        YearMonth lastYear = month.minusYears(1);

        Map<String, Bucket> expense = withdrawals(businessId, from, to);
        Map<String, Bucket> expensePrev = withdrawals(businessId,
                previous.atDay(1), previous.atEndOfMonth());
        Map<String, Bucket> expenseYear = withdrawals(businessId,
                lastYear.atDay(1), lastYear.atEndOfMonth());

        Map<String, Bucket> income = salesDeposits(businessId, from, to);
        Map<String, Bucket> incomePrev = salesDeposits(businessId,
                previous.atDay(1), previous.atEndOfMonth());

        BigDecimal expenseTotal = total(expense);
        BigDecimal incomeTotal = total(income);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("month", month.toString());
        body.put("expense", expenseBlock(expense, expensePrev, expenseYear, expenseTotal));
        body.put("income", incomeBlock(businessId, from, to, income, incomePrev, incomeTotal,
                total(incomePrev)));
        body.put("highlights", highlights(expense, expensePrev, incomeTotal, total(incomePrev)));
        // 마감되지 않은 달이면 「지금까지」다. 이 키가 없으면 화면이 월말 확정값으로 읽는다.
        if (!to.isBefore(LocalDate.now(clock.withZone(KST)))) {
            body.put("isPartial", true);
        }
        body.put("generatedAt", clock.instant());
        return body;
    }

    // --- 집계 ----------------------------------------------------------------

    private Map<String, Bucket> withdrawals(Long businessId, LocalDate from, LocalDate to) {
        return group(businessId, from, to, "WITHDRAWAL", null);
    }

    private Map<String, Bucket> salesDeposits(Long businessId, LocalDate from, LocalDate to) {
        return group(businessId, from, to, "DEPOSIT", SALES_CHANNELS);
    }

    private Map<String, Bucket> group(Long businessId, LocalDate from, LocalDate to,
                                      String flowType, List<String> codes) {
        String filter = codes == null ? ""
                : " AND c.code IN (%s)".formatted(
                        String.join(",", codes.stream().map(code -> "'" + code + "'").toList()));
        Map<String, Bucket> byCode = new LinkedHashMap<>();
        jdbc.query("""
                SELECT c.code AS code, MAX(c.name) AS name, SUM(t.amount) AS amount
                  FROM core.transaction t
                  JOIN core.transaction_category c ON c.id = t.category_id
                 WHERE t.business_id = ? AND t.transaction_date BETWEEN ? AND ?
                   AND t.flow_type = ?
                   AND t.classification_status = 'CONFIRMED'
                   AND t.is_excluded = FALSE%s
                 GROUP BY c.code
                 ORDER BY 3 DESC
                """.formatted(filter), rs -> {
                    byCode.put(rs.getString("code"),
                            new Bucket(rs.getString("name"), money(rs.getBigDecimal("amount"))));
                }, businessId, from, to, flowType);
        return byCode;
    }

    // --- 조립 ----------------------------------------------------------------

    private Map<String, Object> expenseBlock(Map<String, Bucket> now, Map<String, Bucket> previous,
                                             Map<String, Bucket> lastYear, BigDecimal total) {
        List<Map<String, Object>> rows = new ArrayList<>();
        now.forEach((code, bucket) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", code);
            row.put("categoryName", bucket.name());
            row.put("amount", bucket.amount());
            putIfPresent(row, "share", ratio(bucket.amount(), total));
            putIfPresent(row, "momChangeRate", changeRate(bucket.amount(), amountOf(previous, code)));
            putIfPresent(row, "yoyChangeRate", changeRate(bucket.amount(), amountOf(lastYear, code)));
            rows.add(row);
        });

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("total", total);
        block.put("byCategory", rows);
        // nextMonthEstimate 는 넣지 않는다 — 클래스 javadoc 에 이유가 있다.
        return block;
    }

    private Map<String, Object> incomeBlock(Long businessId, LocalDate from, LocalDate to,
                                            Map<String, Bucket> now, Map<String, Bucket> previous,
                                            BigDecimal total, BigDecimal previousTotal) {
        List<Map<String, Object>> rows = new ArrayList<>();
        now.forEach((code, bucket) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("channel", code);
            row.put("channelName", bucket.name());
            row.put("amount", bucket.amount());
            putIfPresent(row, "share", ratio(bucket.amount(), total));
            rows.add(row);
        });

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("total", total);
        block.put("byChannel", rows);
        putIfPresent(block, "momChangeRate", changeRate(total, previousTotal));
        block.put("byProduct", byProduct(businessId, from, to));
        return block;
    }

    /**
     * 상품별 기여도 (15.3 {@code income.byProduct} · P1-22 연동).
     *
     * <p><b>14.5 가 계산한 값을 그대로 쓴다.</b> 여기서 다시 세면 같은 화면의 두 숫자가 갈린다 —
     * {@code contributionToChange} 는 「이번 달 매출이 왜 줄었나」의 답이고 그 정의는 14.5 에 있다.
     */
    private Map<String, Object> byProduct(Long businessId, LocalDate from, LocalDate to) {
        ShareResponse shares = productSales.shares(businessId, from, to, null);
        boolean available = !shares.items().isEmpty();

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("available", available);
        if (!available) {
            // 등록이 없으면 items 는 빈 배열이고 화면이 등록 유도를 띄운다(명세).
            block.put("items", List.of());
            return block;
        }
        block.put("basisPeriod", Map.of("from", shares.period().from().toString(),
                "to", shares.period().to().toString()));
        block.put("shareBasis", shares.shareBasis());
        block.put("incomplete", shares.incomplete());
        block.put("items", shares.items().stream().map(MonthlyReportService::productRow).toList());
        return block;
    }

    private static Map<String, Object> productRow(ShareItem item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("productKey", item.productKey());
        row.put("productName", item.productName());
        row.put("share", item.share());
        putIfPresent(row, "changeRate", item.changeRate());
        putIfPresent(row, "contributionToChange", item.contributionToChange());
        return row;
    }

    /**
     * 눈에 띄는 변화 (15.3 {@code highlights}).
     *
     * <p><b>문구는 템플릿이다.</b> 수치를 문장으로 옮기는 일에 모델이 필요하지 않고, 부르면
     * 리포트를 열 때마다 비용이 난다. 확정 표현을 쓰지 않는다(도메인 규칙 1).
     *
     * <p><b>카테고리까지만 짚는다.</b> 「우유 단가 상승이 주요 원인」 같은 품목 단위 원인은
     * 품목 가계부(13.6)가 있어야 하고 그것은 아직 없다.
     */
    private List<Map<String, Object>> highlights(Map<String, Bucket> expense,
                                                 Map<String, Bucket> expensePrev,
                                                 BigDecimal incomeTotal,
                                                 BigDecimal incomePrevTotal) {
        List<Map<String, Object>> rows = new ArrayList<>();

        record Moved(String code, String name, BigDecimal amount, BigDecimal rate) {
        }
        List<Moved> moved = new ArrayList<>();
        expense.forEach((code, bucket) -> {
            BigDecimal rate = changeRate(bucket.amount(), amountOf(expensePrev, code));
            if (rate != null
                    && bucket.amount().compareTo(HIGHLIGHT_MIN_AMOUNT) >= 0
                    && rate.abs().compareTo(HIGHLIGHT_MIN_RATE) >= 0) {
                moved.add(new Moved(code, bucket.name(), bucket.amount(), rate));
            }
        });
        moved.sort(Comparator.comparing((Moved m) -> m.rate().abs()).reversed());

        for (Moved m : moved.stream().limit(HIGHLIGHT_MAX).toList()) {
            boolean up = m.rate().signum() > 0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", up ? "EXPENSE_INCREASE" : "EXPENSE_DECREASE");
            row.put("category", m.code());
            row.put("description", "%s가 전월 대비 %s%% %s했습니다."
                    .formatted(m.name(), percent(m.rate()), up ? "증가" : "감소"));
            row.put("sourceRef", Map.of("type", "TRANSACTION", "category", m.code()));
            rows.add(row);
        }

        BigDecimal incomeRate = changeRate(incomeTotal, incomePrevTotal);
        if (incomeRate != null && incomeRate.abs().compareTo(HIGHLIGHT_MIN_RATE) >= 0) {
            boolean up = incomeRate.signum() > 0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", up ? "INCOME_INCREASE" : "INCOME_DECREASE");
            row.put("description", "매출 입금이 전월 대비 %s%% %s했습니다."
                    .formatted(percent(incomeRate), up ? "증가" : "감소"));
            row.put("sourceRef", Map.of("type", "TRANSACTION"));
            rows.add(row);
        }
        return rows;
    }

    // --- 계산 ----------------------------------------------------------------

    private static BigDecimal amountOf(Map<String, Bucket> buckets, String code) {
        Bucket bucket = buckets.get(code);
        return bucket == null ? null : bucket.amount();
    }

    private static BigDecimal total(Map<String, Bucket> buckets) {
        return buckets.values().stream()
                .map(Bucket::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 분모가 0 이면 비중을 내지 않는다 — 「0 중 0%」는 말이 아니다. */
    private static BigDecimal ratio(BigDecimal part, BigDecimal total) {
        return total == null || total.signum() == 0
                ? null
                : part.divide(total, 4, RoundingMode.HALF_UP);
    }

    /**
     * 증감률.
     *
     * <p><b>비교 대상이 없거나 0 이면 내지 않는다.</b> 첫 달에는 전월이 없고, 없던 항목이 생긴
     * 것을 「무한대로 늘었다」고 말할 수 없다 — 그때는 절대액만 읽힌다(브리핑과 같은 규칙).
     */
    private static BigDecimal changeRate(BigDecimal now, BigDecimal before) {
        if (before == null || before.signum() == 0) {
            return null;
        }
        return now.subtract(before).divide(before, 4, RoundingMode.HALF_UP);
    }

    private static String percent(BigDecimal rate) {
        return rate.abs().multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** {@code NUMERIC(19,4)} 합계를 원 단위로. 그냥 두면 화면에 소수점이 찍힌다. */
    private static BigDecimal money(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private record Bucket(String name, BigDecimal amount) {
    }
}
