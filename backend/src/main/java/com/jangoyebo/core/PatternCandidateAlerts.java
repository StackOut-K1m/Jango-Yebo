package com.jangoyebo.core;

import java.util.List;

/**
 * 반복 후보 알림 (P0-04 · API 10.3 {@code PATTERN_CANDIDATE}) — {@code core} 가 밖에 내놓는 공개 API.
 *
 * <p>{@link CashGapAlerts}·{@link SafetyLineAlerts} 와 같은 이유로 여기 있다. 후보가 생겼다는
 * 것을 아는 쪽은 {@code forecast} 이고 알림을 소유한 쪽은 {@code core} 다.
 *
 * <p><b>왜 필요했나.</b> {@code AlertType.PATTERN_CANDIDATE} 는 enum 과 명세에 있었는데
 * <b>만드는 코드가 한 곳도 없었다.</b> 탐지가 돌아 후보가 쌓여도 사장님에게 알리지 않으니
 * 승인 화면에 갈 이유가 없었고, 승인이 없으면 예정 거래가 안 생기고, 예정 거래가 없으면
 * 예상 잔액 곡선이 비는 — MVP 파이프라인에서 <b>사람 앞에서 끊긴 유일한 자리</b>였다.
 *
 * <p><b>제목·본문을 밖에서 받지 않는다.</b> {@link SafetyLineAlerts} 와 다른 점인데, 저쪽은
 * 하회 구간마다 원인과 금액이 달라 문구가 매번 다르고 이쪽은 「몇 건이 기다린다」 하나뿐이다.
 * 문구를 {@code core} 안에 두면 도메인 규칙 1(확정 표현 금지)을 한 자리에서 지킬 수 있다.
 */
public interface PatternCandidateAlerts {

    /**
     * 승인 대기 후보 수에 알림을 맞춘다.
     *
     * <p><b>건마다 올리지 않고 사업장당 하나로 모은다.</b> 처음 연결한 계좌는 후보가 스무 개씩
     * 나오는데 그만큼 알림을 만들면 공백·안전선 경고가 그 밑에 묻힌다.
     *
     * <p><b>「생겼다」가 아니라 「지금 몇 건이 기다린다」로 둔다.</b> 사건을 기록하면 승인을
     * 마친 뒤에도 알림이 남아 「처리했는데 계속 경고」가 된다. 그래서 탐지·승인·무시 모두
     * 이 메서드를 부르고, {@code pendingCount} 가 0 이면 알림을 내린다.
     *
     * @param pendingCount 지금 {@code CANDIDATE} 상태인 패턴 수. 0 이면 내린다
     * @param sampleNames  화면이 「배달의민족 정산 외 2건」을 말할 수 있게 하는 이름 몇 개.
     *                     전부 보낼 필요는 없다
     */
    void sync(Long businessId, int pendingCount, List<String> sampleNames);
}
