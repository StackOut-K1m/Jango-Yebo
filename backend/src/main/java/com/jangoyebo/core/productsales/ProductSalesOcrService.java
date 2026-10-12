package com.jangoyebo.core.productsales;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.job.JobType;
import com.jangoyebo.core.storage.UploadStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 매출현황 인쇄물 OCR 등록 (API 14.7, P1-22).
 *
 * <p><b>이것이 1차 입력 경로다.</b> 한 달 80행을 손으로 치는 사람은 없다. 사장님이 POS 에서
 * 「상품별 매출현황」을 인쇄하고 그 출력물을 촬영해 올린다 — 우리는 POS 와 API 로 연동하지
 * 않으므로 이것이 유일한 자동 입력 경로다.
 *
 * <p><b>결과를 저장하지 않는다.</b> 추출값은 후보이고, 사용자가 검토 화면에서 확인·수정한 뒤
 * 14.4 가 저장한다. OCR 이 실패해도 손으로 이어갈 수 있어야 하고, 신규 상품 확인·합계 체크섬과
 * 함께 오인식에 대한 삼중 방어가 된다. 추출 <b>기록</b>(기간·읽은 열·합계 행·검산·모델 메타)만
 * {@link ProductSalesExtractionService} 가 콜백에서 남긴다.
 */
@Service
public class ProductSalesOcrService {

    private static final Logger log = LoggerFactory.getLogger(ProductSalesOcrService.class);

    /**
     * 한 번에 올릴 수 있는 장 수.
     *
     * <p>명세에 상한이 없지만 둔다 — 여러 장 곱하기 80행이 워커의 출력 토큰 상한을 밀어내고,
     * 스위퍼 임계값(10분) 안에 끝나야 한다는 제약도 있다. 스크롤 촬영이 10장을 넘으면 기간을
     * 나눠 올리는 편이 낫다.
     */
    private static final int MAX_FILES = 10;

    /**
     * 워커에 넘길 기존 상품명 수.
     *
     * <p>전부 보내면 요청 본문이 커지고 프롬프트가 길어져 <b>정작 인쇄물을 읽을 여유가
     * 줄어든다.</b> 스냅 유도는 자주 팔리는 것만으로 충분하고, 목록에 없는 상품도 워커가
     * 그대로 반환한다(계약 20.9).
     */
    private static final int MAX_KNOWN_PRODUCTS = 300;

    private final UploadStorage storage;
    private final JobService jobService;
    private final ProductSalesPeriodRepository periods;
    private final BusinessGuard guard;

    public ProductSalesOcrService(UploadStorage storage,
                                  JobService jobService,
                                  ProductSalesPeriodRepository periods,
                                  BusinessGuard guard) {
        this.storage = storage;
        this.jobService = jobService;
        this.periods = periods;
        this.guard = guard;
    }

    /**
     * 사진을 받아 추출 Job 을 건다.
     *
     * <p><b>순서가 중요하다.</b> Job 을 먼저 만들어 id 를 받고(키에 들어간다), 파일을 쓰고,
     * 파라미터를 확정한다.
     *
     * <ul>
     *   <li>키가 {@code product-sales/{businessId}/{jobId}-{n}.{ext}} 라(합의 ⑧-1)
     *       <b>Job id 없이는 키를 만들 수 없다.</b></li>
     *   <li>그런데도 폴링 워커가 파일 없는 Job 을 집어 가지 않는 것은, 이 전체가 한
     *       트랜잭션이라 <b>커밋 전에는 Job 행이 워커에게 보이지 않기</b> 때문이다.
     *       커밋이 끝난 시점에는 파일이 이미 디스크에 있다.</li>
     *   <li>파일 쓰기가 실패하면 예외가 올라가 Job 행이 롤백된다 — 워커가 없는 파일을 읽으러
     *       가서 400 을 받는 일이 생기지 않는다. 워커는 {@code < 500} 을 영구 실패로 보므로
     *       그 400 은 재시도로 낫지 않는다.</li>
     * </ul>
     *
     * @return 만들어진 Job. 클라이언트는 {@code GET /jobs/{jobId}} 로 결과를 받는다
     */
    @Transactional
    public Job requestExtraction(Long businessId, List<MultipartFile> files, String captureType) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "사진을 한 장 이상 올려야 합니다.");
        }
        if (files.size() > MAX_FILES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "한 번에 %d장까지 올릴 수 있습니다. 기간을 나눠 올려 주세요.".formatted(MAX_FILES));
        }
        // 확장자 검증을 먼저 끝낸다 — 세 번째 장이 PDF 가 아니라 엑셀이면 Job 을 만들기 전에
        // 알아야 한다. 만든 뒤에 걸리면 빈 Job 이 목록에 남는다.
        List<String> extensions = files.stream().map(storage::extensionOf).toList();
        String capture = captureTypeOf(captureType);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("captureType", capture);
        // IDENTITY 라 save() 가 그 자리에서 INSERT 를 날린다 — id 가 바로 돌아온다.
        Job job = jobService.enqueue(businessId, guard.currentUserId(),
                JobType.PRODUCT_SALES_OCR, params);

        List<String> keys = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            String key = storage.productSalesKey(businessId, job.getId(), i, extensions.get(i));
            storage.write(key, files.get(i));
            keys.add(key);
        }

        Map<String, Object> finalParams = new LinkedHashMap<>(params);
        finalParams.put("fileObjectKeys", keys);
        finalParams.put("knownProducts", knownProducts(businessId));
        job.assignParams(finalParams);

        // 파일명·상품명을 로그에 남기지 않는다(명세 20.1). 장 수와 유형만 남긴다.
        log.info("사업장 {} 매출 인쇄물 추출 접수 — jobId={} {}장 captureType={}",
                businessId, job.getId(), keys.size(), capture);
        return job;
    }

    /**
     * 이미 등록된 상품명. 워커가 이 목록에 스냅되도록 유도해 표기 흔들림과 오인식을 줄인다.
     *
     * <p>첫 등록이면 빈 목록이고 그것이 정상이다 — 스냅할 대상이 없을 뿐 추출은 된다.
     */
    private List<String> knownProducts(Long businessId) {
        List<String> names = periods.findProductNames(businessId);
        return names.size() <= MAX_KNOWN_PRODUCTS
                ? names
                : names.subList(0, MAX_KNOWN_PRODUCTS);
    }

    /**
     * 촬영 유형. 레이아웃·글꼴·방해 요소가 달라 <b>프롬프트와 기대 정확도가 갈린다</b>
     * (계약 20.9) — 화면 촬영은 반사·모아레, 인쇄물은 종이 곡률·그림자가 문제다.
     *
     * <p>안 주면 {@code PRINTOUT} 이다. 14.7 이 1차 경로로 못박은 것이 감열 인쇄물이고,
     * 검토 화면(SC-09-3)은 이 값을 라디오로 항상 함께 보낸다.
     */
    private String captureTypeOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return "PRINTOUT";
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (!"SCREEN".equals(upper) && !"PRINTOUT".equals(upper)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "알 수 없는 촬영 유형입니다: " + raw);
        }
        return upper;
    }
}
