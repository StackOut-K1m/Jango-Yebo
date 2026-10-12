package com.jangoyebo.core.transaction;

import com.jangoyebo.core.TransactionsClassified;
import com.jangoyebo.core.category.TransactionCategory;
import com.jangoyebo.core.category.TransactionCategoryRepository;
import com.jangoyebo.core.counterparty.CounterpartyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 워커의 거래 분류 결과를 거래에 반영한다 (API 20.3 콜백 → {@code TRANSACTION} +
 * {@code TRANSACTION_CLASSIFICATION_HISTORY}).
 *
 * <p><b>한 건이 틀렸다고 전체를 버리지 않는다.</b> 요청 하나가 최대 400건이라 그중 하나의
 * 분류 코드가 허용 목록 밖이면 나머지 399건까지 잃는다. 공통 규약이 정해 둔 처리도 같다 —
 * 허용 카테고리 외 값과 근거 없는 수치는 폐기하고 <b>해당 항목만</b> 제외한다(20.1).
 * 반영되지 않은 거래는 {@code UNCLASSIFIED} 로 남아 8.5 재실행이 주워간다.
 *
 * <p><b>여기서 던지지 않는다.</b> 호출부({@code AiResultApplier.persist})가 try/catch 로
 * 감싸 Job 을 완료로 두는 이유가 있다 — 던지면 {@code complete()} 까지 롤백되고 워커가
 * 5xx 를 받아 같은 결과를 무한히 재전송한다.
 *
 * <p><b>미구현을 적어 둔다.</b> 20.3 은 {@code needsConfirmation} 을 신뢰도 기준 미만
 * <b>이거나 신규 거래처</b>로 정의하는데, 뒤쪽 절은 판정할 수 없다 — 거래처 테이블이
 * 아직 없다(P1-01 2단계). 그래서 지금은 신뢰도 하나로만 가른다. 새 거래처를 놓치는 방향의
 * 오차라 확인 물량이 규격보다 <b>적게</b> 나온다. 거래처 사전이 생기면 여기에 조건이 붙고,
 * 그 사전을 채울 원천이 {@code reason.merchantName} 이다(합의 ③).
 */
@Service
public class TransactionClassificationService {

    private static final Logger log = LoggerFactory.getLogger(TransactionClassificationService.class);

    private final TransactionRepository transactionRepository;
    private final TransactionClassificationHistoryRepository historyRepository;
    private final TransactionCategoryRepository categoryRepository;
    private final CounterpartyService counterpartyService;
    private final TransactionClassificationProperties properties;
    private final ApplicationEventPublisher events;

    public TransactionClassificationService(TransactionRepository transactionRepository,
                                            TransactionClassificationHistoryRepository historyRepository,
                                            TransactionCategoryRepository categoryRepository,
                                            CounterpartyService counterpartyService,
                                            TransactionClassificationProperties properties,
                                            ApplicationEventPublisher events) {
        this.transactionRepository = transactionRepository;
        this.historyRepository = historyRepository;
        this.categoryRepository = categoryRepository;
        this.counterpartyService = counterpartyService;
        this.properties = properties;
        this.events = events;
    }

    /**
     * 반영 결과. Job 진행률과 이어서 돌릴지 판단하는 데 쓴다.
     *
     * @param classified        분류가 적용된 거래 수
     * @param needsConfirmation 그중 사용자 확인이 필요한 수 (신뢰도 미달)
     * @param skipped           반영하지 않은 결과 수 (거래 없음·확정됨·모르는 분류·타 사업장)
     * @param remaining         반영 후에도 남은 미분류 거래 수. 한 Job 이
     *                          {@code batch-size} 만큼만 담으므로 큰 CSV 는 여러 번 돌아야 한다
     */
    public record Applied(int classified, int needsConfirmation, int skipped, long remaining) {

        static final Applied NONE = new Applied(0, 0, 0, 0);

        /**
         * 이어서 한 번 더 돌려야 하는가.
         *
         * <p><b>진전이 있었을 때만 이어 간다.</b> 남았다는 것만 보고 넣으면, 보낸 20건이 한 건도
         * 반영되지 않는 상황에서 <b>같은 20건을 영원히 다시 보낸다</b> — 호출마다 값을 치르면서
         * 끝나지 않는다. 허용 목록 밖 분류 코드만 돌아오는 경우가 그렇다.
         *
         * <p>한 건이라도 반영됐다면 미분류 수가 반드시 줄므로 언젠가 끝난다. 반영이 0 이면
         * 여기서 멈추고 다음 수집이나 8.5 재실행에 맡긴다.
         */
        public boolean shouldContinue() {
            return classified > 0 && remaining > 0;
        }
    }

    /**
     * 콜백 {@code data} 를 반영한다.
     *
     * @param businessId Job 의 사업장. 결과에 담긴 거래가 이 사업장 것인지 확인하는 데 쓴다
     */
    @Transactional
    public Applied apply(Long businessId, Map<String, Object> data) {
        Map<Long, Map<String, Object>> rows = rowsById(data.get("results"));
        if (rows.isEmpty()) {
            log.warn("분류 결과에 results 가 없다 — 반영하지 않는다");
            return Applied.NONE;
        }

        Map<String, Long> categoryIdByCode = categoryIdByCode();
        List<Transaction> transactions = transactionRepository.findAllById(rows.keySet());
        int skipped = rows.size() - transactions.size();
        if (skipped > 0) {
            // 콜백이 오기 전에 거래가 지워졌다(계좌 해제·재수집). 남은 것만 반영한다.
            log.warn("분류 결과 {}건 중 {}건은 거래가 없다", rows.size(), skipped);
        }

        BigDecimal threshold = properties.confidenceThreshold();
        List<TransactionClassificationHistory> histories = new ArrayList<>();
        int needsConfirmation = 0;

        for (Transaction transaction : transactions) {
            Map<String, Object> row = rows.get(transaction.getId());

            if (!businessId.equals(transaction.getBusinessId())) {
                // 있을 수 없는 일이지만, 나면 남의 사업장 데이터를 쓰는 것이다.
                log.warn("거래 {} 는 사업장 {} 것이 아니다 — 반영하지 않는다",
                        transaction.getId(), businessId);
                skipped++;
                continue;
            }

            String code = asString(row.get("category"));
            Long categoryId = categoryIdByCode.get(code);
            if (categoryId == null) {
                log.warn("거래 {} 의 분류 [{}] 가 허용 목록에 없다 — 이 건만 건너뛴다",
                        transaction.getId(), code);
                skipped++;
                continue;
            }

            BigDecimal confidence = asDecimal(row.get("confidence"));
            boolean confirm = confidence == null || confidence.compareTo(threshold) < 0;

            // 엔티티를 바꾸기 전에 읽어야 한다 — 이력의 previous_* 가 이 값이다.
            ClassificationStatus previousStatus = transaction.getClassificationStatus();
            Long previousCategoryId = transaction.getCategoryId();

            String reason = asString(row.get("reason"));
            if (!transaction.classifyByLlm(categoryId, confidence, confirm, reason)) {
                // 사용자가 이미 확정했다. 제안으로 되돌리지 않는다.
                skipped++;
                continue;
            }

            // 상태는 엔티티에서 읽는다 — 여기서 다시 판정하면 두 곳이 갈라진다.
            histories.add(TransactionClassificationHistory.byLlm(
                    transaction.getId(), previousCategoryId, categoryId, previousStatus,
                    transaction.getClassificationStatus(),
                    confidence, reason, asString(row.get("merchantName"))));
            if (confirm) {
                needsConfirmation++;
            } else {
                // 신뢰도가 기준 이상인 판단만 사전에 담는다. 같은 적요가 다시 와도 모델을
                // 부르지 않기 위해서다 — 실측에서 한 적요가 124번 같은 답으로 분류됐다.
                // 담기는 것은 "확정"이 아니라 "제안"이라, 그 사전으로 분류된 거래는 확인
                // 질문에 남는다(요청 ③ 절충).
                //
                // 열쇠는 적요다. 워커가 주는 merchantName 은 사람이 확인한 이름이 아니라
                // 여기 쓰지 않는다 — 그 값은 8.6 의 표시 이름으로 갈 자리다.
                counterpartyService.rememberSuggestion(businessId, transaction.getDescription(),
                        transaction.getFlowType(), categoryId);
            }
        }

        historyRepository.saveAll(histories);
        if (!histories.isEmpty()) {
            // 반복 패턴의 빈 분류를 채우라는 신호다. 한 건이라도 분류가 붙었으면 보낸다 —
            // 어느 거래가 어느 패턴의 근거인지는 듣는 쪽이 안다.
            events.publishEvent(new TransactionsClassified(businessId));
        }
        // 남은 수를 여기서 센다 — 위 변경이 쿼리 전에 flush 되므로 반영 뒤의 값이다.
        long remaining = transactionRepository.countUnclassified(businessId);
        // 적요·거래처명은 남기지 않는다(20.1 — 워커도 우리도 민감 정보를 로그에 두지 않는다).
        log.info("사업장 {} 거래 분류 반영 — 적용 {}건(확인 필요 {}건) 건너뜀 {}건 남은 미분류 {}건",
                businessId, histories.size(), needsConfirmation, skipped, remaining);
        return new Applied(histories.size(), needsConfirmation, skipped, remaining);
    }

    /**
     * 사용자가 분류를 확정·정정했다 (API 8.3 · 8.4).
     *
     * <p><b>이 이력이 few-shot 의 유일한 원천이다.</b> 남기지 않으면
     * {@link FewShotExampleProvider} 가 영원히 빈 목록을 내고, 프롬프트에 예시가 한 건도 붙지
     * 않는다 — LLM 이 이 사업장의 같은 거래처를 계속 틀린다. 명세 8.3 이 "정정 이력은
     * {@code ClassificationHistory} 에 저장되어 다음 LLM 분류의 few-shot 예시로 사용된다"고
     * 정해 둔 자리다. 정확도 추적(제안 → 정정)의 재료이기도 하다.
     *
     * <p><b>메모·제외를 함께 받는 이유</b>는 8.3 이 PATCH 하나로 오기 때문이다. 분류만 떼어
     * 서비스에 두면 엔티티 메서드를 반으로 갈라 두 번 불러야 하고, 그 사이에 이력이 읽는
     * "이전 값"이 흔들린다.
     *
     * <p><b>바뀐 것이 없으면 남기지 않는다.</b> 이미 확정된 거래를 같은 분류로 다시 PATCH 하는
     * 것은 정정이 아니다. 반대로 <b>LLM 제안을 그대로 수락한 것은 남긴다</b> — 분류는 그대로라도
     * 상태가 {@code SUGGESTED} 에서 {@code CONFIRMED} 로 가고, 사람이 옳다고 한 짝이라
     * 예시로서 가치가 제안과 다르다.
     *
     * @param categoryId {@code null} 이면 분류를 건드리지 않는다 — 메모만 고친 PATCH 다
     * @return 이력을 남겼으면 {@code true}
     */
    @Transactional
    public boolean confirmByUser(Transaction transaction, Long categoryId,
                                 String memo, Boolean excluded, Long userId) {
        // 엔티티를 바꾸기 전에 읽어야 한다 — 이력의 previous_* 가 이 값이다.
        ClassificationStatus previousStatus = transaction.getClassificationStatus();
        Long previousCategoryId = transaction.getCategoryId();

        transaction.classifyByUser(categoryId, memo, excluded);

        if (categoryId == null
                || (previousStatus == ClassificationStatus.CONFIRMED
                    && categoryId.equals(previousCategoryId))) {
            return false;
        }
        historyRepository.save(TransactionClassificationHistory.byUser(
                transaction.getId(), previousCategoryId, categoryId, previousStatus, userId));
        // 확정한 (적요 → 분류) 짝을 사전에 남긴다. 다음에 같은 적요가 오면 LLM 을 부르지 않게
        // 하는 것이 목적이고(도메인 규칙 3), 사전을 보는 쪽은 다음 단계다.
        counterpartyService.rememberConfirmation(transaction.getBusinessId(),
                transaction.getDescription(), transaction.getFlowType(), categoryId);
        events.publishEvent(new TransactionsClassified(transaction.getBusinessId()));
        log.debug("거래 {} 사용자 확정 — {} → {}",
                transaction.getId(), previousCategoryId, categoryId);
        return true;
    }

    /**
     * {@code results} 를 거래 id 로 색인한다.
     *
     * <p>워커가 요청 안에서 20건씩 청킹하므로(합의 ①) 한 콜백에 같은 거래가 두 번 담길 수
     * 있다. 그러면 <b>먼저 온 것을 쓴다</b> — 어느 쪽이 맞다고 볼 근거가 없으니 적어도
     * 결정적으로 고른다. 나면 워커 쪽 버그이므로 로그를 남긴다.
     */
    @SuppressWarnings("unchecked")
    private Map<Long, Map<String, Object>> rowsById(Object raw) {
        if (!(raw instanceof List<?> rows)) {
            return Map.of();
        }
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        int duplicates = 0;
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            Long transactionId = asLong(map.get("transactionId"));
            if (transactionId == null) {
                continue;
            }
            if (byId.putIfAbsent(transactionId, (Map<String, Object>) map) != null) {
                duplicates++;
            }
        }
        if (duplicates > 0) {
            log.warn("분류 결과에 같은 거래가 {}번 중복으로 담겼다 — 먼저 온 것을 쓴다", duplicates);
        }
        return byId;
    }

    /**
     * 코드 → id. <b>활성 카테고리만</b> 받는다.
     *
     * <p>{@code CategoryCatalog.codeById()} 를 쓰지 않는 이유는 그것이 비활성까지 담기
     * 때문이다 — 요청의 {@code allowedCategories} 는 활성만 보내므로(합의 ②) 비활성 코드가
     * 돌아오면 계약 위반이고 받아 주면 안 된다. 23행짜리 고정 시드라 콜백마다 한 번
     * 조회해도 부담이 없다.
     */
    private Map<String, Long> categoryIdByCode() {
        return categoryRepository.findByActiveTrueOrderBySortOrderAsc().stream()
                .collect(Collectors.toMap(TransactionCategory::getCode, TransactionCategory::getId,
                        (a, b) -> a));
    }

    private Long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private String asString(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /** JSON 숫자는 Integer·Double 로 온다. 신뢰도를 double 로 비교하면 경계에서 흔들린다. */
    private BigDecimal asDecimal(Object value) {
        if (value instanceof BigDecimal d) {
            return d;
        }
        return value instanceof Number n ? new BigDecimal(n.toString()) : null;
    }
}
