package com.jangoyebo.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 자산 기반 신용등급 — {@code core} 모듈이 밖에 내놓는 공개 API (P1-14).
 *
 * <p>{@link AccountBalances}·{@link Loans} 와 같은 이유로 여기 있다. 등급 예측(16.2)은
 * <b>예측 곡선을 자산 곡선으로 환산</b>하는 일이라 {@code forecast} 에 있어야 하는데,
 * 현재 자산과 등급 구간은 금융망에서 오므로 {@code core} 가 준다.
 *
 * <h2>이 등급은 실제 신용등급이 아니다</h2>
 *
 * <p>금융망은 <b>수시입출금 + 예·적금 잔액 합계로만</b> 산출한다({@code LOAN_04}).
 * 연체 이력도 소득도 반영되지 않는다. <b>화면에서 실제 신용등급처럼 보이게 하면 안 된다</b> —
 * 「보유 자산 기준 참고 등급」으로 표기한다(기술스택 4.13 함정).
 *
 * <h2>등급은 대표자 단위다</h2>
 *
 * <p>{@code LOAN_04} 는 {@code userKey} 로 조회하고 사업장 개념이 없다. <b>가게가 몇 개든
 * 등급은 하나다.</b> 사업장을 바꿔도 같은 값이므로 「○○ 가게 신용등급」으로 쓰지 않는다.
 */
public interface CreditAssets {

    /**
     * 지금 등급과 자산. 금융망을 부르고 {@code CREDIT_SNAPSHOT} 에 남긴다.
     *
     * <p>이력을 쌓는 이유는 <b>「등급이 언제부터 떨어졌나」</b>를 보여주기 위해서다.
     */
    Snapshot current(Long businessId);

    /**
     * 등급별 자산 구간 ({@code LOAN_01}).
     *
     * <p>앱 전체에 하나뿐인 전역 기준이라 테이블을 두지 않고 <b>서버가 캐시한다</b>(ERD).
     * 자산이 큰 등급부터 온다 — A, B, C, D, E.
     */
    List<GradeCriterion> criteria();

    /**
     * @param grade          {@code A}~{@code E}
     * @param demandDeposit  수시입출금 자산 — 이 사용자의 <b>모든</b> 계좌 합계
     * @param depositSavings 예·적금 자산
     * @param total          합계. 등급을 정하는 값이다
     */
    record Snapshot(String grade, BigDecimal demandDeposit, BigDecimal depositSavings,
                    BigDecimal total, Instant assessedAt) {
    }

    /** @param minAssetValue 이 등급이 되는 최소 자산 */
    record GradeCriterion(String grade, BigDecimal minAssetValue, BigDecimal maxAssetValue) {
    }
}
