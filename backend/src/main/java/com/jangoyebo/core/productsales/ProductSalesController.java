package com.jangoyebo.core.productsales;

import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import com.jangoyebo.core.productsales.ProductSalesDtos.RegisterRequest;
import com.jangoyebo.core.productsales.ProductSalesDtos.RegisterResponse;
import com.jangoyebo.core.productsales.ProductSalesDtos.RenameRequest;
import com.jangoyebo.core.productsales.ProductSalesDtos.RenameResponse;
import com.jangoyebo.core.productsales.ProductSalesDtos.ShareResponse;
import com.jangoyebo.core.productsales.ProductSalesDtos.TrendResponse;
import com.jangoyebo.core.job.JobResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

/**
 * 상품별 매출 구성비 (API 14.4~14.7, P1-22).
 *
 * <p>경로가 {@code /businesses/{businessId}/**} 라 {@link CurrentBusiness} 가 주입 시점에
 * 소유권을 검증한다 — 여기서 따로 가드를 부르지 않는다.
 */
@RestController
@RequestMapping("/api/v1")
public class ProductSalesController {

    private final ProductSalesService service;
    private final ProductSalesOcrService ocrService;

    public ProductSalesController(ProductSalesService service, ProductSalesOcrService ocrService) {
        this.service = service;
        this.ocrService = ocrService;
    }

    /**
     * 인쇄물 OCR 등록 (14.7). {@code 202} — 사진을 받고 추출 Job 을 건 상태다.
     *
     * <p><b>이것이 1차 입력 경로다.</b> 한 달 80행을 손으로 치는 사람은 없다. 사장님이 POS 에서
     * 「상품별 매출현황」을 인쇄하고 그 출력물을 촬영해 올린다.
     *
     * <p><b>결과는 저장되지 않는다.</b> {@code GET /jobs/{jobId}} 로 추출값을 받아 검토 화면에
     * 띄우고, 사용자가 확인·수정한 뒤 14.4 로 저장한다 — OCR 이 실패해도 손으로 이어갈 수
     * 있어야 한다.
     *
     * <p>파트 이름은 {@code files} 다(명세 표기의 {@code files[]} 는 "여러 장"이라는 뜻이다).
     * 화면 촬영은 스크롤 때문에, 인쇄물은 길이 때문에 여러 장이 된다.
     *
     * @param captureType {@code PRINTOUT}(기본) · {@code SCREEN}. 레이아웃·글꼴·방해 요소가
     *                    달라 프롬프트와 기대 정확도가 갈린다
     */
    @PostMapping(value = "/businesses/{businessId}/product-sales/ocr",
            consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<JobResponse> requestOcr(
            @CurrentBusiness Long businessId,
            @RequestPart("files") List<MultipartFile> files,
            // 파일만 파트고 나머지는 폼 필드다. @RequestPart 로 받으면 Content-Type 이 붙은
            // 파트만 찾아서 일반 텍스트 필드가 안 들어온다(13.1 에서 같은 함정을 밟았다).
            @RequestParam(value = "captureType", required = false) String captureType) {

        return ApiResponse.ok(JobResponse.from(
                ocrService.requestExtraction(businessId, files, captureType)));
    }

    /**
     * 상품별 매출 등록 (14.4).
     *
     * <p><b>같은 기간 재등록은 그 기간을 통째로 교체한다.</b> 오입력 정정이 잦아 409 를 내지
     * 않는다. 이번 제출에서 빠진 상품은 지워진다 — 지난번 오인식이 분모에 남으면 안 된다.
     *
     * <p>체크섬이 어긋나도({@code MISMATCHED}) 저장한다. ⚠ <b>그건 오류가 아니다</b> —
     * 실측 라벨 90개 중 20개(22.2%)가 그렇고, 인쇄물이 여러 장으로 잘리면 합계 행은 기간
     * 전체를 담기 때문이다. 화면이 빨간 경고로 그리면 사장님이 매달 경고를 본다.
     */
    @PostMapping("/businesses/{businessId}/product-sales")
    public ApiResponse<RegisterResponse> register(@CurrentBusiness Long businessId,
                                                  @Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(service.register(businessId, request));
    }

    /**
     * 구성비 조회 (14.5).
     *
     * <p>기간을 안 주면 <b>이번 달 1일부터 오늘까지</b>다. 입력 단위가 월이라 그 창이 자연스럽다.
     *
     * <p>등록이 없으면 빈 목록이다 — 404 가 아니다. 아직 인쇄물을 안 올린 사업장이 정상이다.
     *
     * @param sort {@code AMOUNT}(기본) · {@code QUANTITY} · {@code NAME} · {@code CHANGE}.
     *             {@code AMOUNT} 는 <b>구성비 기준 금액</b> 내림차순이다 — 명세는 결제금액
     *             기준이라고 하는데 그 열이 없는 인쇄물이 있어 총매출로 내려가는 경우를 포함한다
     */
    @GetMapping("/businesses/{businessId}/product-sales")
    public ApiResponse<ShareResponse> shares(
            @CurrentBusiness Long businessId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String sort) {

        LocalDate toDate = to != null ? to : LocalDate.now();
        LocalDate fromDate = from != null ? from : toDate.withDayOfMonth(1);
        return ApiResponse.ok(service.shares(businessId, fromDate, toDate, sort));
    }

    /**
     * 상품명 일괄 변경 (14.6).
     *
     * <p><b>마스터 테이블이 없으므로 표기 흔들림·오타 정리의 유일한 수단이다.</b> 사업장의
     * <b>모든 기간</b>에 적용한다 — 한 기간만 고치면 추이 조회에서 같은 상품이 두 줄로 갈라진
     * 채 남는다. 같은 기간에 양쪽이 다 있으면 수량·금액을 합산한다.
     *
     * <p>⚠ <b>두 이름이 정규화하면 같은 키일 수 있다.</b> 명세 예시의
     * {@code "카페 라떼" → "카페라떼"} 가 그 경우인데(공백은 정규화가 이미 지운다) 두 행은
     * 처음부터 한 행이었다. 그때는 합칠 것이 없고 보이는 글자만 바뀌며, 응답의
     * {@code keyUnchanged} 가 그 사실을 알린다. 실제로 합쳐지는 것은 {@code 카페라며 →
     * 카페라떼} 처럼 글자가 진짜 다른 경우다.
     *
     * <p>바꿀 상품이 하나도 없으면 {@code 400} 이다 — 0건으로 조용히 답하면 사용자는 바뀐 줄
     * 안다.
     */
    @PatchMapping("/businesses/{businessId}/product-sales/rename")
    public ApiResponse<RenameResponse> rename(@CurrentBusiness Long businessId,
                                              @Valid @RequestBody RenameRequest request) {
        return ApiResponse.ok(service.rename(businessId, request));
    }

    /**
     * 상품 하나의 기간별 추이 (14.5).
     *
     * <p>기본 창은 <b>최근 12개월</b>이다. 월 단위 등록이라 열두 점이면 계절성이 보인다.
     * <b>일자별은 제공하지 않는다</b> — 일 단위 원본을 받지 않는다.
     *
     * <p>{@code productKey} 는 원문 상품명을 넣어도 된다. 서버가 같은 규칙으로 정규화한다.
     */
    @GetMapping("/businesses/{businessId}/product-sales/{productKey}")
    public ApiResponse<TrendResponse> trend(
            @CurrentBusiness Long businessId,
            @PathVariable String productKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDate toDate = to != null ? to : LocalDate.now();
        LocalDate fromDate = from != null ? from : toDate.minusMonths(12);
        return ApiResponse.ok(service.trend(businessId, productKey, fromDate, toDate));
    }
}
