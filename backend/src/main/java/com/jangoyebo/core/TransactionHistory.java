package com.jangoyebo.core;

import com.jangoyebo.common.domain.FlowType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 반복 패턴 탐지가 읽는 과거 거래 — {@code core} 모듈이 밖에 내놓는 두 번째 공개 API.
 *
 * <p>{@link CategoryCatalog} 와 같은 이유로 여기 있다. {@code core} 는 CLOSED 라
 * {@code core.transaction.Transaction} 을 {@code forecast} 가 참조할 수 없다
 * ({@code ModularityTests} 가 "non-exposed type" 으로 잡는다).
 *
 * <p><b>엔티티가 아니라 읽기 전용 투영을 내놓는다.</b> 탐지에 필요한 것은 여덟 필드뿐이고,
 * 엔티티를 넘기면 {@code forecast} 가 거래를 수정할 수 있게 되어 소유권이 흐려진다.
 */
public interface TransactionHistory {

    /**
     * 탐지 대상 거래를 날짜 오름차순으로 읽는다.
     *
     * <p><b>예측에서 제외한 거래({@code is_excluded})는 빼고 준다.</b> 사용자가 "이건 일회성"
     * 이라고 표시한 것을 다시 반복 후보로 올리면 같은 판단을 계속 요구하게 된다.
     */
    List<HistoricalTransaction> findForPatternDetection(Long businessId, LocalDate from, LocalDate to);

    /**
     * 금융망 거래고유번호 → 우리 거래 id (API 9.6 정확 매칭).
     *
     * <p>자동 반영이 만든 거래는 <b>어느 예정 거래의 것인지 우리가 안다</b> — 보낼 때 번호를
     * 받아 적어 두었기 때문이다. 그래서 금액·날짜로 추정하는 최소 매처(9.5)를 거치지 않고
     * 바로 붙일 수 있다. 따라잡기로 며칠 늦게 실행된 거래는 매처의 허용폭(±1~3일) 밖이라
     * <b>이 경로가 없으면 영영 안 붙는다.</b>
     *
     * <p><b>{@code is_excluded}·계좌 상태로 거르지 않는다.</b> 여기서 하는 일은 「이 번호의
     * 거래가 우리 DB 에 있나」이고, 예측에 넣을지는 그것과 별개다
     * ({@link #findForPatternDetection} 과 규칙이 다른 이유).
     *
     * @return 찾은 것만. 아직 동기화되지 않은 번호는 키 자체가 없다
     */
    Map<String, Long> findIdsByExternalTransactionIds(Long accountId, Collection<String> externalIds);

    /**
     * 거래 id → 분류 id (반복 패턴 분류 백필 · {@code RecurringPatternService.backfillCategories}).
     *
     * <p>패턴은 탐지 시점에 표본 거래의 분류를 복사해 둔다. 분류가 그보다 늦게 돌면 그 값이
     * 비는데, 재탐지는 후보만 갱신하므로 <b>승인된 패턴은 영영 빈 채로 남는다.</b> 그때
     * 패턴이 근거로 삼은 거래들이 지금 무슨 분류인지 되묻는 것이 이 조회다.
     *
     * <p><b>{@link #findForPatternDetection} 의 술어를 쓰지 않는다.</b> 저쪽은 탐지 창과
     * 제외·계좌 상태로 거르는데, 여기 오는 id 는 이미 패턴이 근거로 고른 것이라 다시 거를
     * 이유가 없다. 계좌가 그 뒤 해제돼도 「그때 그 거래가 무슨 분류였나」는 변하지 않는다.
     *
     * <p><b>분류가 없는 거래는 키 자체가 없다.</b> 「분류가 비었다」와 「거래가 없다」를
     * 호출자가 가를 이유가 없다 — 둘 다 채울 값이 없다는 뜻이다.
     */
    Map<Long, Long> categoryIdsOf(Long businessId, Collection<Long> transactionIds);

    /**
     * @param categoryId  미분류면 null
     * @param description 적요. 금융망 응답에 거래처명이 없어 <b>사실상 유일한 그룹핑 근거</b>다
     */
    record HistoricalTransaction(
            Long transactionId,
            Long accountId,
            Long categoryId,
            LocalDate date,
            FlowType flowType,
            BigDecimal amount,
            String description) {
    }
}
