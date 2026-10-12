package com.jangoyebo.core.transaction;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.core.counterparty.Counterparty;
import com.jangoyebo.core.counterparty.CounterpartyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 거래처 사전으로 먼저 분류한다 (명세 8.5 — "규칙 → 거래처 사전 순으로 먼저 처리하고,
 * 남은 거래만 배치로 묶어 AI 워커에 전달한다").
 *
 * <p><b>도메인 규칙 3 이 실제로 지켜지는 자리다.</b> "확정된 거래처↔분류 매핑은 DB에 캐시해서
 * 재사용한다. LLM 호출은 처음 보는 패턴에만." 이 단계가 없으면 지난달 사장님이 확정한 것과
 * 같은 적요의 이번 달 거래도 값을 다시 치른다.
 *
 * <p><b>LLM 을 부르기 전에 돈다.</b> 여기서 걸러진 거래는 워커 요청에 담기지 않는다 —
 * 걸러내는 것이 목적이지 두 번 분류하는 것이 아니다.
 *
 * <p><b>규칙 단계(CLASSIFICATION_RULE)는 아직 없다.</b> 명세의 파이프라인 세 단계 중 가운데만
 * 있는 상태이고, 규칙이 생기면 이 앞에 붙는다.
 */
@Service
public class DictionaryClassificationService {

    private static final Logger log = LoggerFactory.getLogger(DictionaryClassificationService.class);

    /**
     * 한 번에 훑을 미분류 거래 수.
     *
     * <p>LLM 배치 상한과 <b>따로 둔다.</b> 사전 분류는 값이 들지 않으므로 20건씩 끊을 이유가
     * 없고, 큰 CSV 를 올린 직후 사전에 있는 것부터 한꺼번에 채워지는 편이 화면에서 낫다.
     * 한 트랜잭션에 담기는 양이라 무한정 늘리지는 않는다.
     */
    private static final int WINDOW = 500;

    private final TransactionRepository transactionRepository;
    private final TransactionClassificationHistoryRepository historyRepository;
    private final CounterpartyRepository counterpartyRepository;

    public DictionaryClassificationService(TransactionRepository transactionRepository,
                                           TransactionClassificationHistoryRepository historyRepository,
                                           CounterpartyRepository counterpartyRepository) {
        this.transactionRepository = transactionRepository;
        this.historyRepository = historyRepository;
        this.counterpartyRepository = counterpartyRepository;
    }

    /**
     * 사전에 있는 적요를 분류한다.
     *
     * @return 분류한 거래 수. 0 이면 사전이 비었거나 전부 처음 보는 적요다
     */
    @Transactional
    public int classify(Long businessId) {
        List<Transaction> unclassified =
                transactionRepository.findUnclassified(businessId, PageRequest.of(0, WINDOW));
        if (unclassified.isEmpty()) {
            return 0;
        }

        Set<String> descriptions = unclassified.stream()
                .map(Transaction::getDescription)
                .filter(description -> description != null && !description.isBlank())
                .map(String::strip)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (descriptions.isEmpty()) {
            return 0;
        }

        // (적요, 방향) 으로 찾는다. 같은 적요가 입금과 출금 양쪽에 오므로 — 배달 플랫폼은
        // 정산금 입금과 광고비 출금이 둘 다 법인명으로 찍힌다 — 적요만 맞춰 보면 출금 거래에
        // 입금 카테고리가 붙는다. 카테고리는 방향이 고정돼 있다(입금 7종·출금 16종).
        Map<Key, Counterparty> byKey = counterpartyRepository
                .findByBusinessIdAndActiveTrueAndDefaultCategoryIdNotNullAndCanonicalNameIn(
                        businessId, descriptions)
                .stream()
                .collect(Collectors.toMap(
                        c -> new Key(c.getCanonicalName(), c.getDefaultFlowType()),
                        Function.identity(), (a, b) -> a));
        if (byKey.isEmpty()) {
            return 0;
        }

        List<TransactionClassificationHistory> histories = new ArrayList<>();
        for (Transaction transaction : unclassified) {
            String description = transaction.getDescription();
            Counterparty counterparty = description == null ? null
                    : byKey.get(new Key(description.strip(), transaction.getFlowType()));
            if (counterparty == null) {
                continue;
            }

            // 엔티티를 바꾸기 전에 읽어야 한다 — 이력의 previous_* 가 이 값이다.
            ClassificationStatus previousStatus = transaction.getClassificationStatus();
            Long previousCategoryId = transaction.getCategoryId();
            String reason = reasonOf(counterparty);

            // 출처로 갈라 묻지 않는다 — 사전에 담기는 짝은 사장님이 확정했거나 신뢰도 0.85
            // 이상인 모델 판단뿐이고, 둘 다 이미 확정 기준을 넘었다. 근거 문장만 갈린다.
            if (!transaction.classifyByDictionary(
                    counterparty.getDefaultCategoryId(), reason)) {
                continue;
            }
            // 상태는 엔티티에서 읽는다 — 여기서 다시 판정하면 두 곳이 갈라진다.
            histories.add(TransactionClassificationHistory.byDictionary(
                    transaction.getId(), previousCategoryId, counterparty.getDefaultCategoryId(),
                    previousStatus, transaction.getClassificationStatus(), reason));
        }

        historyRepository.saveAll(histories);
        if (!histories.isEmpty()) {
            log.info("사업장 {} 거래처 사전으로 {}건 분류 — LLM 을 부르지 않는다",
                    businessId, histories.size());
        }
        return histories.size();
    }

    /** 사전의 열쇠. 적요만으로는 방향이 갈리는 거래처를 가를 수 없다. */
    private record Key(String canonicalName, FlowType flowType) {
    }

    /**
     * 화면 상세 패널이 보여줄 근거 (8.2 {@code classification.reason}).
     *
     * <p>예측이 아니라 <b>지난 일의 사실</b>이라 단정해서 적는다 — 도메인 규칙 1 의 "확정 표현
     * 금지"는 예측에 대한 것이다. 이 문장이 없으면 사장님은 왜 분류가 붙었는지 알 수 없다.
     *
     * <p>출처에 따라 문장이 갈린다. 모델이 담아 둔 짝은 <b>사람이 본 적이 없다</b>는 사실을
     * 그대로 적는다 — 확인 질문이 함께 뜨는 이유가 그것이기 때문이다.
     */
    /**
     * 왜 이렇게 분류됐는지 사장님이 읽을 한 문장.
     *
     * <p>출처가 상태를 가르지는 않지만(둘 다 확정) <b>근거는 다르다.</b> 하나는 사장님 자신의
     * 지난 결정이고 하나는 모델 판단의 재사용이라, 나중에 "이건 왜 임차료였지"를 되짚을 때
     * 무엇을 의심해야 하는지가 달라진다.
     */
    private String reasonOf(Counterparty counterparty) {
        return counterparty.isUserConfirmed()
                ? "같은 적요를 이전에 %d회 확정하셨습니다.".formatted(counterparty.getConfirmedCount())
                : "같은 적요를 이전에 AI 가 같은 분류로 판단했습니다.";
    }
}
