package com.jangoyebo.core.alert;

import com.jangoyebo.core.PatternCandidateAlerts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link PatternCandidateAlerts} 구현. {@link AlertService} 를 감싸 {@code PATTERN_CANDIDATE}
 * 하나로 좁힌다({@link CashGapAlertService}·{@link SafetyLineAlertService} 와 같은 모양).
 */
@Service
public class PatternCandidateAlertService implements PatternCandidateAlerts {

    /**
     * 사업장당 하나. 건별로 나누지 않으므로 키가 고정이고, 같은 키로 다시 올리면
     * {@link AlertService#raise} 가 건수만 갱신한다.
     */
    private static final String DEDUP_KEY = "pattern-candidate";

    /** 제목에 이름을 몇 개까지 적을지. 길어지면 목록에서 줄바꿈이 나 읽기 나빠진다. */
    private static final int SAMPLE_LIMIT = 2;

    private final AlertService alerts;

    public PatternCandidateAlertService(AlertService alerts) {
        this.alerts = alerts;
    }

    @Override
    @Transactional
    public void sync(Long businessId, int pendingCount, List<String> sampleNames) {
        if (pendingCount <= 0) {
            alerts.resolve(businessId, AlertType.PATTERN_CANDIDATE, DEDUP_KEY);
            return;
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("pendingCount", pendingCount);
        detail.put("samples", samples(sampleNames));

        alerts.raise(businessId, AlertType.PATTERN_CANDIDATE, DEDUP_KEY,
                title(pendingCount, samples(sampleNames)),
                // 「확인하면」이지 「확인하세요」가 아니다 — 승인은 사용자 판단이고 서비스가
                // 시킬 일이 아니다(도메인 규칙 5). 무엇이 달라지는지만 말한다.
                "확인하면 예상 잔액 곡선에 반영됩니다.",
                Map.of(), detail);
    }

    /**
     * 「배달의민족 정산 외 2건」. 이름을 하나도 못 받으면 건수만 적는다 — 빈 따옴표가 남으면
     * 화면이 이름을 못 읽은 것처럼 보인다.
     */
    private static String title(int pendingCount, List<String> samples) {
        if (samples.isEmpty()) {
            return "새 반복 거래 후보 %d건".formatted(pendingCount);
        }
        String names = String.join(" · ", samples);
        int rest = pendingCount - samples.size();
        return rest > 0
                ? "새 반복 거래 후보 %s 외 %d건".formatted(names, rest)
                : "새 반복 거래 후보 %s".formatted(names);
    }

    private static List<String> samples(List<String> sampleNames) {
        return sampleNames == null ? List.of()
                : sampleNames.stream().filter(name -> name != null && !name.isBlank())
                        .limit(SAMPLE_LIMIT).toList();
    }
}
