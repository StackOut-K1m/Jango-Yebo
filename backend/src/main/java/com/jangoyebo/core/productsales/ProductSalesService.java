package com.jangoyebo.core.productsales;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.productsales.ProductSalesDtos.Checksum;
import com.jangoyebo.core.productsales.ProductSalesDtos.ChecksumDetail;
import com.jangoyebo.core.productsales.ProductSalesDtos.ItemRequest;
import com.jangoyebo.core.productsales.ProductSalesDtos.Period;
import com.jangoyebo.core.productsales.ProductSalesDtos.RegisterRequest;
import com.jangoyebo.core.productsales.ProductSalesDtos.RegisterResponse;
import com.jangoyebo.core.productsales.ProductSalesDtos.RenameRequest;
import com.jangoyebo.core.productsales.ProductSalesDtos.RenameResponse;
import com.jangoyebo.core.productsales.ProductSalesDtos.ShareItem;
import com.jangoyebo.core.productsales.ProductSalesDtos.ShareResponse;
import com.jangoyebo.core.productsales.ProductSalesDtos.SimilarName;
import com.jangoyebo.core.productsales.ProductSalesDtos.Totals;
import com.jangoyebo.core.productsales.ProductSalesDtos.TrendPoint;
import com.jangoyebo.core.productsales.ProductSalesDtos.TrendResponse;
import com.jangoyebo.core.productsales.ProductSalesDtos.Warning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 상품별 매출 등록·조회 (API 14.4~14.5, P1-22).
 *
 * <p><b>저장 목적은 매출 절대액이 아니라 구성비다.</b> 금액의 단일 출처는 계좌 입금
 * ({@code TRANSACTION})이고 여기 숫자는 상품 간 비율의 분자로만 쓴다 — 세무 집계·부가세·
 * 잔액 예측 어디에도 들어가지 않는다(ERD §1.9 「소비처와 비소비처」).
 *
 * <p>계산 규칙은 {@link ProductShares} 에 순수 함수로 있다. 여기는 DB 와 경계 처리만 한다.
 */
@Service
public class ProductSalesService {

    private static final Logger log = LoggerFactory.getLogger(ProductSalesService.class);

    /** 출처. DB {@code ck_ps_period_source} 와 값이 같아야 한다. */
    private static final Set<String> SOURCE_TYPES = Set.of("OCR", "OCR_CORRECTED", "MANUAL");

    private static final String MATCHED = "MATCHED";
    private static final String MISMATCHED = "MISMATCHED";
    private static final String UNAVAILABLE = "UNAVAILABLE";

    private final ProductSalesPeriodRepository periods;

    public ProductSalesService(ProductSalesPeriodRepository periods) {
        this.periods = periods;
    }

    // ------------------------------------------------------------------ 14.4 등록

    /**
     * 기간 단위 등록. <b>같은 기간 재등록은 그 기간을 통째로 교체한다.</b>
     *
     * <p>명세가 "upsert 로 갱신"이라고 한 것을 <b>교체</b>로 읽었다. 순수 upsert 면 지난번에
     * 잘못 읽힌 유령 상품이 이번 제출에서 빠져도 그대로 남고, <b>그 행이 구성비 분모에
     * 계속 들어간다.</b> 인쇄물 한 장은 그 기간 전체를 말하는 입력이므로 교체가 맞다.
     * 기간이 겹치되 동일하지 않으면(12/1~12/14 위에 12/1~12/31) 서로 건드리지 않고 둘 다 남는다.
     */
    @Transactional
    public RegisterResponse register(Long businessId, RegisterRequest request) {
        LocalDate from = request.periodStart();
        LocalDate to = request.periodEnd();
        if (to.isBefore(from)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "집계 종료일이 시작일보다 빠릅니다.");
        }
        String defaultSource = sourceTypeOf(request.sourceType(), "OCR");

        // 키를 먼저 만든다 — 중복은 UK 위반으로 터지기 전에 여기서 잡아야 한다.
        Map<String, ItemRequest> byKey = new LinkedHashMap<>();
        for (ItemRequest item : request.items()) {
            validateAmounts(item);
            String key = ProductKeys.normalize(item.productName());
            ItemRequest previous = byKey.put(key, item);
            if (previous != null) {
                // 합치면 조용히 두 배가 된다. 20.9 워커가 장 경계 중복을 이미 제거하므로
                // 여기까지 온 것은 검토 화면에서 손으로 만든 중복이다.
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "같은 상품이 두 번 들어 있습니다: " + item.productName());
            }
        }

        boolean bootstrap = !periods.existsByBusinessId(businessId);
        List<String> knownKeys = bootstrap ? List.of() : periods.findProductKeys(businessId);
        Set<String> known = Set.copyOf(knownKeys);

        ChecksumDetail detail = checksum(request.checksum(), request.items());
        String checksumStatus = statusOf(detail);

        int removed = replacePeriod(businessId, from, to, byKey, defaultSource,
                checksumStatus, request.extractionId());

        List<String> newProducts = new ArrayList<>();
        List<SimilarName> warnings = new ArrayList<>();
        byKey.forEach((key, item) -> {
            if (known.contains(key)) {
                return;
            }
            newProducts.add(item.productName());
            // 첫 등록은 전 품목이 신규라 비교할 기존 표기 자체가 없다.
            ProductNameSimilarity.closest(key, knownKeys)
                    .ifPresent(similar -> warnings.add(new SimilarName(item.productName(), similar)));
        });

        // 상품명·금액을 로그에 남기지 않는다(명세 20.1). 건수와 판정만 남긴다.
        log.info("사업장 {} 상품별 매출 등록 — 기간 {}~{} 저장 {}건 삭제 {}건 체크섬={} 신규 {}건",
                businessId, from, to, byKey.size(), removed, checksumStatus, newProducts.size());

        return new RegisterResponse(from, to, byKey.size(), removed,
                ProductShares.basisOf(linesOf(request.items())).name(),
                checksumStatus, detail, newProducts, bootstrap, warnings);
    }

    /**
     * 같은 기간의 행을 교체한다.
     *
     * @return 이번 제출에 없어서 지운 행 수
     */
    private int replacePeriod(Long businessId, LocalDate from, LocalDate to,
                              Map<String, ItemRequest> byKey, String defaultSource,
                              String checksumStatus, Long extractionId) {
        Map<String, ProductSalesPeriod> existing =
                periods.findByBusinessIdAndPeriodStartAndPeriodEnd(businessId, from, to).stream()
                        .collect(LinkedHashMap::new,
                                (m, row) -> m.put(row.getProductKey(), row),
                                LinkedHashMap::putAll);

        byKey.forEach((key, item) -> {
            ProductSalesPeriod row = existing.remove(key);
            boolean isNew = row == null;
            if (isNew) {
                row = ProductSalesPeriod.of(
                        businessId, extractionId, from, to, item.productName(), key);
            }
            row.fill(item.quantity(), item.grossAmount(), item.discountAmount(),
                    item.netAmount(), item.vatAmount(), item.billedAmount(),
                    item.productName(), sourceTypeOf(item.sourceType(), defaultSource),
                    checksumStatus, extractionId);
            if (isNew) {
                // 채운 뒤에 저장한다. IDENTITY 라 save() 가 그 자리에서 INSERT 를 날리는데,
                // 먼저 부르면 source_type·checksum_status 가 NOT NULL 위반으로 터진다.
                periods.save(row);
            }
        });

        // 남은 것은 지난번에는 있었고 이번에는 없는 행이다.
        existing.values().forEach(periods::delete);
        return existing.size();
    }

    /**
     * 금액 관계 검증.
     *
     * <p><b>네 값이 모두 있을 때만 본다</b>(14.4). 하나라도 없으면 건너뛴다 — 58/80mm 인쇄물은
     * 열이 줄어 나오므로, 없는 값을 0 으로 보고 검증하면 정상 인쇄물이 전부 거부된다.
     */
    private void validateAmounts(ItemRequest item) {
        if (item.grossAmount() == null && item.billedAmount() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "총매출과 결제금액 중 하나는 있어야 합니다: " + item.productName());
        }
        if (item.grossAmount() == null || item.discountAmount() == null
                || item.netAmount() == null || item.vatAmount() == null) {
            return;
        }
        BigDecimal left = item.grossAmount().subtract(item.discountAmount());
        BigDecimal right = item.netAmount().add(item.vatAmount());
        if (left.compareTo(right) != 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "금액이 맞지 않습니다: " + item.productName()
                            + " (총매출−할인 " + left + " ≠ 공급가+부가세 " + right + ")");
        }
    }

    /**
     * 항목 합과 인쇄된 합계 행을 대조한다.
     *
     * <p>대조하는 열은 <b>구성비 기준과 같다</b> — 결제금액이 있으면 그것, 없으면 총매출.
     * 분모로 쓸 값이 맞는지를 보는 것이 목적이라 다른 열을 보면 의미가 없다.
     */
    private ChecksumDetail checksum(Checksum printed, List<ItemRequest> items) {
        if (printed == null) {
            return null;
        }
        boolean useBilled = printed.billedAmount() != null;
        BigDecimal printedTotal = useBilled ? printed.billedAmount() : printed.grossAmount();
        if (printedTotal == null) {
            return null;
        }
        String field = useBilled ? "billedAmount" : "grossAmount";

        BigDecimal itemsTotal = items.stream()
                .map(i -> useBilled ? i.billedAmount() : i.grossAmount())
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal difference = printedTotal.subtract(itemsTotal);
        if (difference.signum() == 0) {
            return new ChecksumDetail(field, itemsTotal, printedTotal, difference, null);
        }
        // 「아래쪽 행이 빠진 것 같습니다」는 확정 표현이 아니다. 실제로 잘린 인쇄물이 22.2% 다.
        String message = difference.signum() > 0
                ? "합계와 %s원 차이가 있습니다. 아래쪽 행이 빠진 것 같습니다.".formatted(format(difference))
                : "항목 합이 인쇄된 합계보다 %s원 많습니다. 같은 행이 두 번 읽혔을 수 있습니다."
                        .formatted(format(difference.negate()));
        return new ChecksumDetail(field, itemsTotal, printedTotal, difference, message);
    }

    private String statusOf(ChecksumDetail detail) {
        if (detail == null) {
            return UNAVAILABLE;
        }
        return detail.difference().signum() == 0 ? MATCHED : MISMATCHED;
    }

    private String sourceTypeOf(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!SOURCE_TYPES.contains(upper)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "알 수 없는 출처입니다: " + raw);
        }
        return upper;
    }

    // ------------------------------------------------------------------ 14.6 일괄 변경

    /**
     * 상품명 일괄 변경.
     *
     * <p><b>마스터 테이블이 없으므로 표기 흔들림·오타 정리의 유일한 수단이다.</b> 과거 전체에
     * 적용한다 — 한 기간만 고치면 추이 조회에서 같은 상품이 두 줄로 갈라진 채 남는다.
     *
     * <p>두 갈래로 갈린다.
     *
     * <ul>
     *   <li><b>정규화하면 같은 키</b>({@code 카페 라떼} → {@code 카페라떼}) — 두 행은 처음부터
     *       한 행이었다. 합칠 것이 없고 <b>보이는 글자만</b> 바뀐다. 명세 예시가 이 경우인데,
     *       거절하지 않는 것은 그것도 쓸모 있는 동작이기 때문이다.</li>
     *   <li><b>키가 다름</b>({@code 카페라며} → {@code 카페라떼}) — 기간마다 대상 키가 있으면
     *       <b>수량·금액을 합치고</b> 원래 행을 지운다. 없으면 그 기간에 대상 키로 행을 새로
     *       만들고 옮긴다.</li>
     * </ul>
     *
     * <p><b>합칠 때 없는 열을 0 으로 채우지 않는다</b>({@link ProductShares#sum}) — 58mm 인쇄물의
     * 빠진 총매출 열이 "0원"이 되면 안 된다.
     */
    @Transactional
    public RenameResponse rename(Long businessId, RenameRequest request) {
        String fromName = request.from().trim();
        String toName = request.to().trim();
        if (fromName.equals(toName)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "바꾸기 전과 후가 같습니다.");
        }
        String fromKey = ProductKeys.normalize(fromName);
        String toKey = ProductKeys.normalize(toName);

        List<ProductSalesPeriod> rows = periods.findByProductKeys(businessId,
                fromKey.equals(toKey) ? Set.of(fromKey) : Set.of(fromKey, toKey));
        List<ProductSalesPeriod> mine = rows.stream()
                .filter(r -> fromKey.equals(r.getProductKey()))
                .toList();
        if (mine.isEmpty()) {
            // 화면이 목록에서 고르는 값이라 여기 걸리면 목록이 낡은 것이다. 조용히 0건으로
            // 답하면 사용자는 바뀐 줄 안다.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "바꿀 상품이 없습니다: " + fromName);
        }

        if (fromKey.equals(toKey)) {
            mine.forEach(row -> row.rename(toName));
            log.info("사업장 {} 상품 표기 변경 — 키 동일, {}개 기간", businessId, mine.size());
            return new RenameResponse(fromName, toName, toKey, true, mine.size(), 0,
                    periodsOf(mine));
        }

        Map<Period, ProductSalesPeriod> targets = new LinkedHashMap<>();
        rows.stream().filter(r -> toKey.equals(r.getProductKey()))
                .forEach(r -> targets.put(new Period(r.getPeriodStart(), r.getPeriodEnd()), r));

        int renamed = 0;
        int merged = 0;
        for (ProductSalesPeriod row : mine) {
            ProductSalesPeriod target = targets.get(new Period(row.getPeriodStart(), row.getPeriodEnd()));
            if (target == null) {
                // 그 기간에 대상 상품이 없었다. 키가 UK 의 일부라 행을 옮기는 대신 다시 만든다.
                ProductSalesPeriod moved = ProductSalesPeriod.of(businessId, row.getExtractionId(),
                        row.getPeriodStart(), row.getPeriodEnd(), toName, toKey);
                moved.fill(row.getQuantity(), row.getGrossAmount(), row.getDiscountAmount(),
                        row.getNetAmount(), row.getVatAmount(), row.getBilledAmount(),
                        toName, correctedSource(row.getSourceType()), row.getChecksumStatus(),
                        row.getExtractionId());
                periods.save(moved);
                renamed++;
            } else {
                target.fill(
                        ProductShares.sum(target.getQuantity(), row.getQuantity()),
                        ProductShares.sum(target.getGrossAmount(), row.getGrossAmount()),
                        ProductShares.sum(target.getDiscountAmount(), row.getDiscountAmount()),
                        ProductShares.sum(target.getNetAmount(), row.getNetAmount()),
                        ProductShares.sum(target.getVatAmount(), row.getVatAmount()),
                        ProductShares.sum(target.getBilledAmount(), row.getBilledAmount()),
                        toName, correctedSource(target.getSourceType()),
                        target.getChecksumStatus(), target.getExtractionId());
                merged++;
            }
            periods.delete(row);
        }

        // 상품명을 로그에 남기지 않는다(명세 20.1). 건수만 남긴다.
        log.info("사업장 {} 상품명 일괄 변경 — 이동 {}건 병합 {}건", businessId, renamed, merged);
        return new RenameResponse(fromName, toName, toKey, false, renamed, merged, periodsOf(mine));
    }

    /** 사람이 손으로 고쳤다는 사실을 출처에 남긴다. {@code MANUAL} 은 원래 사람이 넣은 것이다. */
    private String correctedSource(String sourceType) {
        return "OCR".equals(sourceType) ? "OCR_CORRECTED" : sourceType;
    }

    private List<Period> periodsOf(List<ProductSalesPeriod> rows) {
        return rows.stream()
                .map(r -> new Period(r.getPeriodStart(), r.getPeriodEnd()))
                .distinct()
                .sorted(Comparator.comparing(Period::from).thenComparing(Period::to))
                .toList();
    }

    // ------------------------------------------------------------------ 14.5 조회

    /**
     * 구성비 조회.
     *
     * <p>등록이 없으면 <b>빈 상태로 답한다</b> — 404 가 아니다(화면구성도 6.4). 아직 인쇄물을
     * 안 올린 사업장이 정상이고, 화면은 등록 안내를 띄운다.
     *
     * @param sort {@code AMOUNT}(기본, 구성비 기준 금액 내림차순) · {@code QUANTITY} ·
     *             {@code NAME} · {@code CHANGE}
     */
    @Transactional(readOnly = true)
    public ShareResponse shares(Long businessId, LocalDate from, LocalDate to, String sort) {
        if (to.isBefore(from)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "조회 종료일이 시작일보다 빠릅니다.");
        }
        List<ProductSalesPeriod> rows = periods.findOverlapping(businessId, from, to);
        List<Warning> warnings = new ArrayList<>(rangeWarnings(rows, from, to));

        List<ProductShares.Line> current = ProductShares.merge(linesOfRows(rows));
        warnings.addAll(partialColumnWarnings(rows));

        // 직전 <동일 길이> 기간. 12/1~12/14 를 물으면 11/17~11/30 이 비교 대상이다.
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate priorTo = from.minusDays(1);
        List<ProductShares.Line> previous = ProductShares.merge(
                linesOfRows(periods.findOverlapping(businessId, priorTo.minusDays(days - 1), priorTo)));

        List<ShareItem> items = new ArrayList<>(
                ProductShares.metrics(current, previous).stream().map(ShareItem::of).toList());
        items.sort(comparatorOf(sort));

        ProductShares.Basis basis = ProductShares.basisOf(current);
        if (basis == ProductShares.Basis.GROSS && current.stream().anyMatch(l -> l.billedAmount() != null)) {
            warnings.add(new Warning("MIXED_SHARE_BASIS",
                    "결제금액이 없는 상품이 섞여 있어 총매출 기준으로 계산했습니다."));
        }

        return new ShareResponse(
                new Period(from, to),
                basis.name(),
                rows.stream().anyMatch(r -> MISMATCHED.equals(r.getChecksumStatus())),
                Totals.of(ProductShares.totals(current)),
                items,
                warnings);
    }

    /**
     * 상품 하나의 기간별 추이.
     *
     * <p><b>일자별은 없다</b> — 일 단위 원본을 받지 않으므로 등록 기간이 곧 해상도다.
     * {@code share} 는 <b>그 기간 안에서의</b> 점유율이라 기간마다 분모가 다르다.
     */
    @Transactional(readOnly = true)
    public TrendResponse trend(Long businessId, String productKey, LocalDate from, LocalDate to) {
        String key = ProductKeys.normalize(productKey);
        List<ProductSalesPeriod> rows = periods.findOverlapping(businessId, from, to);

        Map<Period, List<ProductSalesPeriod>> byPeriod = new LinkedHashMap<>();
        rows.stream()
                .sorted(Comparator.comparing(ProductSalesPeriod::getPeriodStart)
                        .thenComparing(ProductSalesPeriod::getPeriodEnd))
                .forEach(r -> byPeriod
                        .computeIfAbsent(new Period(r.getPeriodStart(), r.getPeriodEnd()),
                                p -> new ArrayList<>())
                        .add(r));

        String displayName = null;
        List<TrendPoint> points = new ArrayList<>();
        for (Map.Entry<Period, List<ProductSalesPeriod>> entry : byPeriod.entrySet()) {
            ProductSalesPeriod mine = entry.getValue().stream()
                    .filter(r -> key.equals(r.getProductKey()))
                    .findFirst().orElse(null);
            if (mine == null) {
                // 그 기간에 안 팔린 상품이다. 0 으로 채우지 않는다 — 안 판 것과 못 읽은 것이
                // 구분되지 않게 된다.
                continue;
            }
            displayName = mine.getProductName();
            List<ProductShares.Line> lines = ProductShares.merge(linesOfRows(entry.getValue()));
            ProductShares.Metrics m = ProductShares.metrics(lines, List.of()).stream()
                    .filter(x -> key.equals(x.productKey()))
                    .findFirst().orElseThrow();

            points.add(new TrendPoint(entry.getKey().from(), entry.getKey().to(),
                    ProductShares.basisOf(lines).name(),
                    m.quantity(), m.grossAmount(), m.discountAmount(), m.billedAmount(),
                    m.share(), m.discountRate(), mine.getChecksumStatus()));
        }
        return new TrendResponse(key, displayName, points);
    }

    // ------------------------------------------------------------------ 내부

    private List<ProductShares.Line> linesOf(List<ItemRequest> items) {
        return items.stream()
                .map(i -> new ProductShares.Line(
                        ProductKeys.normalize(i.productName()), i.productName(), i.quantity(),
                        i.grossAmount(), i.discountAmount(), i.netAmount(), i.vatAmount(),
                        i.billedAmount()))
                .toList();
    }

    private List<ProductShares.Line> linesOfRows(List<ProductSalesPeriod> rows) {
        return rows.stream()
                .sorted(Comparator.comparing(ProductSalesPeriod::getPeriodStart))
                .map(r -> new ProductShares.Line(
                        r.getProductKey(), r.getProductName(), r.getQuantity(),
                        r.getGrossAmount(), r.getDiscountAmount(), r.getNetAmount(),
                        r.getVatAmount(), r.getBilledAmount()))
                .toList();
    }

    /**
     * 숫자를 그대로 믿으면 안 되는 사정 둘.
     *
     * <ul>
     *   <li>{@code PERIODS_OVERLAP} — 등록 기간이 서로 겹친다. 겹친 날의 매출이 두 번 더해진다</li>
     *   <li>{@code PERIOD_PARTIALLY_OUTSIDE} — 등록 기간이 조회 범위 밖으로 걸쳐 있다.
     *       기간 집계라 잘라 쓸 수 없어 통째로 넣었고, 그만큼 <b>물어본 것보다 넓은 기간</b>의
     *       숫자다</li>
     * </ul>
     */
    private List<Warning> rangeWarnings(List<ProductSalesPeriod> rows, LocalDate from, LocalDate to) {
        List<Warning> warnings = new ArrayList<>();
        List<Period> distinct = rows.stream()
                .map(r -> new Period(r.getPeriodStart(), r.getPeriodEnd()))
                .distinct()
                .sorted(Comparator.comparing(Period::from).thenComparing(Period::to))
                .toList();

        for (int i = 0; i < distinct.size(); i++) {
            Period p = distinct.get(i);
            if (p.from().isBefore(from) || p.to().isAfter(to)) {
                warnings.add(new Warning("PERIOD_PARTIALLY_OUTSIDE",
                        "등록 기간 %s~%s 가 조회 범위 밖으로 걸쳐 있어 통째로 더했습니다."
                                .formatted(p.from(), p.to())));
            }
            if (i > 0 && !distinct.get(i - 1).to().isBefore(p.from())) {
                warnings.add(new Warning("PERIODS_OVERLAP",
                        "등록 기간 %s~%s 와 %s~%s 가 겹칩니다. 겹친 날이 두 번 더해집니다."
                                .formatted(distinct.get(i - 1).from(), distinct.get(i - 1).to(),
                                        p.from(), p.to())));
            }
        }
        return warnings;
    }

    /**
     * 어떤 등록에는 있고 어떤 등록에는 없는 열.
     *
     * <p>합계가 <b>일부 기간만 더한 값</b>이 된다. 0 으로 채우는 쪽이 더 나쁘지만
     * ({@code ProductShares.sum} 참고) 그렇다고 조용히 내보내면 화면이 그걸 전체 합으로 읽는다.
     */
    private List<Warning> partialColumnWarnings(List<ProductSalesPeriod> rows) {
        record Column(String name, Function<ProductSalesPeriod, BigDecimal> get) { }
        List<Column> columns = List.of(
                new Column("quantity", ProductSalesPeriod::getQuantity),
                new Column("grossAmount", ProductSalesPeriod::getGrossAmount),
                new Column("discountAmount", ProductSalesPeriod::getDiscountAmount),
                new Column("netAmount", ProductSalesPeriod::getNetAmount),
                new Column("vatAmount", ProductSalesPeriod::getVatAmount),
                new Column("billedAmount", ProductSalesPeriod::getBilledAmount));

        Set<String> partial = new LinkedHashSet<>();
        for (Column column : columns) {
            boolean some = rows.stream().anyMatch(r -> column.get().apply(r) != null);
            boolean missing = rows.stream().anyMatch(r -> column.get().apply(r) == null);
            if (some && missing) {
                partial.add(column.name());
            }
        }
        return partial.isEmpty() ? List.of() : List.of(new Warning("PARTIAL_COLUMN",
                "일부 인쇄물에만 있던 열이 있어 합계가 그 기간만 더한 값입니다: "
                        + String.join(", ", partial)));
    }

    private Comparator<ShareItem> comparatorOf(String sort) {
        String key = sort == null ? "AMOUNT" : sort.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (key) {
            case "QUANTITY" -> nullsLastDesc(ShareItem::quantity);
            case "NAME" -> Comparator.comparing(ShareItem::productName,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "CHANGE" -> nullsLastDesc(ShareItem::contributionToChange);
            case "AMOUNT" -> nullsLastDesc(i -> i.billedAmount() != null
                    ? i.billedAmount() : i.grossAmount());
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "알 수 없는 정렬 기준입니다: " + sort);
        };
    }

    /** 값이 없는 행은 뒤로 보낸다 — 열이 없는 인쇄물이 목록 맨 위를 차지하면 안 된다. */
    private Comparator<ShareItem> nullsLastDesc(Function<ShareItem, BigDecimal> field) {
        return Comparator.comparing(field,
                Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private String format(BigDecimal amount) {
        return String.format("%,d", amount.setScale(0, java.math.RoundingMode.HALF_UP).longValue());
    }
}
