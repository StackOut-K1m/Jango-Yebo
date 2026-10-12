package com.jangoyebo.core.transaction;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.business.Business;
import com.jangoyebo.core.business.BusinessRepository;
import com.jangoyebo.core.category.TransactionCategory;
import com.jangoyebo.core.category.TransactionCategoryRepository;
import com.jangoyebo.core.transaction.TransactionClassificationRequest.BusinessContext;
import com.jangoyebo.core.transaction.TransactionClassificationRequest.TransactionToClassify;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 워커에 보낼 분류 요청을 조립한다 (API 20.3).
 *
 * <p>핸들러({@code core.ai})가 아니라 여기에 있는 이유는 재료가 전부 이 모듈의 것이기
 * 때문이다 — 미분류 거래, 카테고리 목록, 사업장 업종, 사용자 정정 이력. 핸들러는 조립된
 * 본문을 워커에 넘기는 일만 한다.
 */
@Component
public class TransactionClassificationRequestFactory {

    private static final Logger log =
            LoggerFactory.getLogger(TransactionClassificationRequestFactory.class);

    /**
     * 업종이 비어 있을 때 보낼 값.
     *
     * <p>워커 스키마의 {@code industry_type} 은 <b>필수</b>이고 우리 컬럼은 nullable 이다
     * (사업장 등록에서 업종은 선택이다). 비우고 보내면 요청 전체가 422 이므로 허용 값 중
     * 하나로 맞춘다 — {@code OTHER} 는 "업종 미지정"을 있는 그대로 옮긴 값이다.
     *
     * <p>값 자체를 검증하지는 않는다. 게이트는 {@code BusinessDtos} 의 {@code @Pattern} 이고,
     * 여기서 목록을 한 벌 더 들면 <b>업종 어휘가 세 곳</b>(워커·DTO·여기)에 갈린다.
     */
    private static final String UNKNOWN_INDUSTRY = "OTHER";

    private final TransactionRepository transactionRepository;
    private final TransactionCategoryRepository categoryRepository;
    private final BusinessRepository businessRepository;
    private final FewShotExampleProvider fewShotExampleProvider;
    private final TransactionClassificationProperties properties;

    public TransactionClassificationRequestFactory(
            TransactionRepository transactionRepository,
            TransactionCategoryRepository categoryRepository,
            BusinessRepository businessRepository,
            FewShotExampleProvider fewShotExampleProvider,
            TransactionClassificationProperties properties) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.businessRepository = businessRepository;
        this.fewShotExampleProvider = fewShotExampleProvider;
        this.properties = properties;
    }

    /**
     * 미분류 거래를 모아 요청을 만든다.
     *
     * @return 보낼 것이 없으면 {@code Optional.empty()}. <b>정상 상태다</b> — 트리거가 쿼리
     *         기반이라(합의 ④) 큐에 들어온 뒤 다른 경로가 먼저 분류했거나 사용자가 직접
     *         분류했을 수 있다. 워커 스키마는 {@code transactions} 를 1건 이상으로 요구하므로
     *         빈 요청을 보내면 422 다
     */
    @Transactional(readOnly = true)
    public Optional<TransactionClassificationRequest> create(Long jobId, Long businessId) {
        List<Transaction> unclassified = transactionRepository.findUnclassified(
                businessId, PageRequest.of(0, properties.batchSize()));
        List<TransactionToClassify> transactions = toRequestRows(unclassified);
        if (transactions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TransactionClassificationRequest(
                jobId,
                new BusinessContext(industryTypeOf(businessId)),
                allowedCategories(),
                fewShotExampleProvider.forBusiness(businessId),
                transactions));
    }

    /**
     * 적요가 없는 거래는 보내지 않는다.
     *
     * <p>워커 스키마가 {@code description} 을 필수로 받는 데다, 금융망이 주는 정보가 적요 한
     * 줄이 전부다(거래처명 필드가 없다). 적요가 비면 모델에게 남는 것이 금액과 방향뿐이라
     * 분류가 아니라 추측이 된다 — <b>값을 치르고 추측을 사지 않는다.</b> 그 거래는
     * {@code UNCLASSIFIED} 로 남아 사용자가 직접 고르게 된다(8.3).
     */
    private List<TransactionToClassify> toRequestRows(List<Transaction> unclassified) {
        List<TransactionToClassify> rows = new ArrayList<>(unclassified.size());
        int withoutDescription = 0;
        for (Transaction transaction : unclassified) {
            String description = WorkerText.clip(transaction.getDescription());
            if (description == null) {
                withoutDescription++;
                continue;
            }
            rows.add(TransactionToClassify.of(
                    transaction.getId(),
                    description,
                    transaction.getFlowType(),
                    // 소수점은 버린다. 워커 스키마가 정수(ge=0)이고, 부호는 flow_type 이
                    // 들고 있으며 DB 의 ck_tx_amount 가 양수를 보장한다.
                    transaction.getAmount().longValue(),
                    transaction.getOccurredAt()));
        }
        if (withoutDescription > 0) {
            log.info("적요가 없어 분류에서 제외한 거래 {}건", withoutDescription);
        }
        return rows;
    }

    /**
     * 활성 카테고리 코드 전부, 시드 순서 그대로.
     *
     * <p>업종별로 골라 보내지 않는다 — 워커의 프리픽스 캐시가 정확 일치라 사업장마다 목록이
     * 갈리면 캐시도 갈린다(합의 ②). {@code sort_order} 순은 시드 파일의 순서와 같고, 중요한
     * 것은 <b>모든 사업장에 같은 순서</b>라는 점이다.
     */
    private List<String> allowedCategories() {
        return categoryRepository.findByActiveTrueOrderBySortOrderAsc().stream()
                .map(TransactionCategory::getCode)
                .toList();
    }

    /** 사업장이 지워진 뒤에 Job 이 돌면 재시도해도 같다 — 즉시 실패로 확정한다. */
    private String industryTypeOf(Long businessId) {
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_NOT_FOUND));
        String industryCode = business.getIndustryCode();
        return industryCode == null || industryCode.isBlank() ? UNKNOWN_INDUSTRY : industryCode;
    }
}
