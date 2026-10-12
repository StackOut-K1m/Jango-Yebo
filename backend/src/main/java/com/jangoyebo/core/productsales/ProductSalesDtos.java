package com.jangoyebo.core.productsales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 상품별 매출 등록·조회의 요청·응답 (API 14.4~14.5, P1-22). */
public final class ProductSalesDtos {

    private ProductSalesDtos() {
    }

    // ------------------------------------------------------------------ 14.4 등록

    /**
     * 기간 단위 등록 (14.4).
     *
     * <p><b>일 단위가 아니다.</b> POS 「상품별 매출현황」의 기본 조회 단위가 `영업일자 from ~ to`
     * 이고, 한 달 80행을 손으로 치는 사람은 없어 1차 입력 경로가 인쇄물 촬영(14.7)이다.
     *
     * @param sourceType  기본 출처. 행마다 다르면 {@link ItemRequest#sourceType} 으로 덮는다
     * @param checksum    인쇄물 합계 행. 있으면 서버가 항목 합과 대조한다. 없으면
     *                    {@code checksumStatus = UNAVAILABLE} 이다
     * @param extractionId <b>명세 밖 확장.</b> 14.7 이 남긴 추출 기록과 이 행들을 잇는다 —
     *                    나중에 구성비가 틀린 것으로 드러났을 때 "모델이 그때 무엇을 읽었나"에
     *                    답하려면 이 연결이 있어야 한다. 수기 입력이면 생략한다
     */
    public record RegisterRequest(@NotNull LocalDate periodStart,
                                  @NotNull LocalDate periodEnd,
                                  String sourceType,
                                  @Valid Checksum checksum,
                                  Long extractionId,
                                  @NotEmpty @Valid List<ItemRequest> items) {
    }

    /** 인쇄물 맨 아래 합계 행. 항목 합과 대조하는 무료 정합성 검사다. */
    public record Checksum(BigDecimal quantity,
                           BigDecimal grossAmount,
                           BigDecimal billedAmount) {
    }

    /**
     * 상품 한 줄.
     *
     * <p><b>{@code grossAmount} 와 {@code billedAmount} 중 하나는 있어야 한다.</b> 둘 다 없으면
     * 구성비의 분모를 만들 수 없다. 반대로 <b>둘 다 요구하지는 않는다</b> — 58mm 감열지에는
     * 총매출 열이 인쇄되지 않아(실측 행 33.3%) 필수로 걸면 정상 인쇄물을 반려하게 된다.
     *
     * <p><b>수량도 필수가 아니다.</b> 수량 열이 없는 인쇄물이 있고, 구성비는 금액으로 만든다.
     * 무게 단위 상품(정육·반찬)이 있어 정수도 아니다.
     *
     * <p><b>취소·반품은 음수로 넣는다.</b> 서버는 양수 제약을 걸지 않는다.
     */
    public record ItemRequest(@NotBlank @Size(max = 255) String productName,
                              BigDecimal quantity,
                              BigDecimal grossAmount,
                              BigDecimal discountAmount,
                              BigDecimal netAmount,
                              BigDecimal vatAmount,
                              BigDecimal billedAmount,
                              String sourceType) {
    }

    /**
     * 등록 결과 (14.4).
     *
     * @param newProducts 이번에 처음 등장한 상품. 클라이언트가 저장 전 사용자 확인을 받는
     *                    장치이며, 마스터 테이블 없이 OCR 오인식을 거르는 방법이다
     * @param bootstrap   사업장 최초 등록. 첫 달은 전 품목이 신규라 80개를 하나씩 확인시키는
     *                    것이 의미가 없어 <b>일괄 수락</b>한다
     * @param warnings    기존 상품과 표기가 비슷한 신규 상품. 월 단위 입력은 중간 검토가 없어
     *                    오인식이 다음 달까지 살아남으므로 이 경고가 일 단위보다 중요하다
     */
    public record RegisterResponse(LocalDate periodStart,
                                   LocalDate periodEnd,
                                   int savedCount,
                                   int removedCount,
                                   String shareBasis,
                                   String checksumStatus,
                                   ChecksumDetail checksumDetail,
                                   List<String> newProducts,
                                   boolean bootstrap,
                                   List<SimilarName> warnings) {
    }

    /**
     * 합계 행과 얼마나 어긋났나.
     *
     * <p>⚠ <b>어긋나도 저장한다.</b> 막으면 인쇄물이 잘린 사장님이 아무것도 넣을 수 없다.
     * 그리고 어긋나는 것이 드물지 않다 — 실측 라벨 90개 중 20개(22.2%)가 그렇고 그것이
     * 정상이다(인쇄물이 여러 장으로 잘리면 합계 행은 기간 전체를 담는다).
     */
    public record ChecksumDetail(String field,
                                 BigDecimal itemsTotal,
                                 BigDecimal printedTotal,
                                 BigDecimal difference,
                                 String message) {
    }

    public record SimilarName(String productName, String similarTo) {
    }

    // ------------------------------------------------------------------ 14.5 조회

    /**
     * 구성비 조회 (14.5).
     *
     * @param incomplete 조회 기간에 {@code checksumStatus = MISMATCHED} 인 등록이 섞여 있다.
     *                   구성비는 계산하되 화면이 「불완전」을 표시한다
     * @param warnings   기간이 서로 겹치거나 조회 범위 밖으로 걸쳐 있는 경우 등. 숫자를
     *                   그대로 믿으면 안 되는 사정을 담는다
     */
    public record ShareResponse(Period period,
                                String shareBasis,
                                boolean incomplete,
                                Totals totals,
                                List<ShareItem> items,
                                List<Warning> warnings) {
    }

    public record Period(LocalDate from, LocalDate to) {
    }

    public record Totals(BigDecimal quantity,
                         BigDecimal grossAmount,
                         BigDecimal discountAmount,
                         BigDecimal netAmount,
                         BigDecimal vatAmount,
                         BigDecimal billedAmount) {

        static Totals of(ProductShares.Line line) {
            return new Totals(line.quantity(), line.grossAmount(), line.discountAmount(),
                    line.netAmount(), line.vatAmount(), line.billedAmount());
        }
    }

    /**
     * 상품 한 줄의 지표.
     *
     * <p><b>여기에 수익성 지표는 없다.</b> 원가를 모르므로 점유율·증감·할인율까지만 말한다 —
     * 매출 1위 상품이 적자일 수 있고, 마진 계산은 레시피·매입단가가 필요한 P2-03 범위다.
     *
     * @param changeRate           직전 동일 길이 기간 대비 증감률. <b>직전 기간에 등록이 아예
     *                             없으면 null</b> — 비교 대상이 없는 것을 "전부 신규 성장"으로
     *                             말하면 안 된다
     * @param contributionToChange 총매출 변동 중 이 상품이 차지한 금액. "이번 달 매출이 왜
     *                             줄었나"에 답하는 지표이고 P1-19 리포트·P1-17 브리핑이 재사용한다
     */
    public record ShareItem(String productKey,
                            String productName,
                            BigDecimal quantity,
                            BigDecimal grossAmount,
                            BigDecimal discountAmount,
                            BigDecimal netAmount,
                            BigDecimal vatAmount,
                            BigDecimal billedAmount,
                            BigDecimal share,
                            BigDecimal discountRate,
                            BigDecimal changeRate,
                            BigDecimal contributionToChange) {

        static ShareItem of(ProductShares.Metrics m) {
            return new ShareItem(m.productKey(), m.productName(), m.quantity(),
                    m.grossAmount(), m.discountAmount(), m.netAmount(), m.vatAmount(),
                    m.billedAmount(), m.share(), m.discountRate(), m.changeRate(),
                    m.contributionToChange());
        }
    }

    /** 숫자를 그대로 믿으면 안 되는 사정. {@code code} 로 화면이 문구를 고른다. */
    public record Warning(String code, String message) {
    }

    // ------------------------------------------------------------------ 14.6 일괄 변경

    /**
     * 상품명 일괄 변경 (14.6).
     *
     * <p><b>마스터 테이블이 없으므로 표기 흔들림·오타 정리의 유일한 수단이다.</b> 상품 마스터를
     * 두지 않은 배경은 ERD §1.9 를 본다.
     *
     * <p>⚠ <b>두 이름이 정규화하면 같은 키일 수 있다.</b> 명세 예시의
     * {@code "카페 라떼" → "카페라떼"} 가 정확히 그 경우인데, 공백은
     * {@link ProductKeys#normalize} 가 이미 지워서 <b>두 행은 처음부터 한 행</b>이었다.
     * 그때는 합칠 것이 없고 <b>보이는 글자만</b> 바뀐다 — 그것도 쓸모 있는 동작이라 거절하지
     * 않는다. 실제로 합쳐지는 것은 {@code 카페라며 → 카페라떼}(오독)나
     * {@code 아메리카노(HOT) → 아메리카노}(괄호 변형)처럼 <b>글자가 진짜 다른</b> 경우다.
     */
    public record RenameRequest(@NotBlank @Size(max = 255) String from,
                                @NotBlank @Size(max = 255) String to) {
    }

    /**
     * @param keyUnchanged 두 이름이 같은 키였다. 합친 행 없이 표시 이름만 바뀌었다는 뜻이다
     * @param renamedCount 대상 키가 없어 그냥 옮긴 기간 수
     * @param mergedCount  대상 키가 이미 있어 <b>수량·금액을 합친</b> 기간 수
     */
    public record RenameResponse(String from,
                                 String to,
                                 String productKey,
                                 boolean keyUnchanged,
                                 int renamedCount,
                                 int mergedCount,
                                 List<Period> periods) {
    }

    /**
     * 상품 하나의 기간별 추이 (14.5 두 번째 엔드포인트).
     *
     * <p><b>일자별은 없다.</b> 일 단위 원본을 받지 않으므로 등록 기간이 곧 해상도다.
     */
    public record TrendResponse(String productKey,
                                String productName,
                                List<TrendPoint> items) {
    }

    /** {@code share} 는 <b>그 기간 안에서의</b> 점유율이다 — 월별 라떼 구성비 변화가 이것이다. */
    public record TrendPoint(LocalDate periodStart,
                             LocalDate periodEnd,
                             String shareBasis,
                             BigDecimal quantity,
                             BigDecimal grossAmount,
                             BigDecimal discountAmount,
                             BigDecimal billedAmount,
                             BigDecimal share,
                             BigDecimal discountRate,
                             String checksumStatus) {
    }
}
