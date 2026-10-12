package com.jangoyebo.core.transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 사업장별 few-shot 예시를 <b>하루 단위로 고정</b>해 내놓는다 (API 20.3).
 *
 * <p><b>캐시가 이 클래스의 존재 이유다.</b> 워커의 프롬프트 프리픽스 캐시는 정확 일치라,
 * 예시 목록이 요청마다 달라지면 적중률이 0 이 된다(합의 ①·②). 그래서 같은 날 같은 사업장은
 * 몇 번을 물어도 <b>같은 목록</b>을 받는다.
 *
 * <p><b>대가를 적어 둔다 — 오늘 사용자가 정정한 것은 내일부터 반영된다.</b> 즉시 반영과
 * 캐시 적중은 함께 가질 수 없고, 예시 한 건이 그날 안에 들어가는 이득이 프리픽스 캐시를
 * 매번 깨는 손실보다 크지 않다. 분류 자체는 캐시와 무관하게 오늘 정정한 값을 본다 —
 * 흐려지는 것은 "다음 거래를 가르치는 속도"뿐이다.
 *
 * <p>캐시는 프로세스 메모리다. 사업장당 항목 하나이고 재기동하면 비는데, 둘 다 문제가 되지
 * 않는다 — 비면 다시 조회할 뿐이고 값은 DB 에서 온다. Redis 로 옮기면 인스턴스 사이에도
 * 같은 목록이 되지만, 지금은 단일 인스턴스라 이득이 없다.
 */
@Component
public class FewShotExampleProvider {

    private static final Logger log = LoggerFactory.getLogger(FewShotExampleProvider.class);

    /** 하루의 경계는 사장님의 하루다. UTC 로 끊으면 오전 9시에 목록이 바뀐다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 중복을 걸러내려면 필요한 수보다 넉넉히 읽어야 한다.
     *
     * <p>같은 거래처의 확정이 수십 건씩 쌓이므로(매달 같은 임대인·같은 플랫폼) 필요한 수만
     * 읽으면 목록이 한 거래처로 도배된다. 3배로 잡은 것은 측정값이 아니라 여유값이다.
     */
    private static final int OVERFETCH = 3;

    private final TransactionClassificationHistoryRepository repository;
    private final TransactionClassificationProperties properties;
    private final Clock clock;
    private final Map<Long, Snapshot> cache = new ConcurrentHashMap<>();

    public FewShotExampleProvider(TransactionClassificationHistoryRepository repository,
                                  TransactionClassificationProperties properties,
                                  Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** 그날 그 사업장의 예시 목록. 이력이 없으면 빈 목록이다(계약에서 선택 필드다). */
    public List<FewShotExample> forBusiness(Long businessId) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        Snapshot cached = cache.get(businessId);
        if (cached != null && cached.day().equals(today)) {
            return cached.examples();
        }
        List<FewShotExample> examples = load(businessId);
        cache.put(businessId, new Snapshot(today, examples));
        log.debug("사업장 {} few-shot 예시 {}건 갱신 — {} 하루 고정", businessId, examples.size(), today);
        return examples;
    }

    /**
     * <b>같은 (적요, 분류) 짝은 한 번만 넣는다.</b> 배달 정산 확정 20건은 하나보다 더
     * 가르치는 것이 없고, 그만큼 다른 거래처가 예시에서 밀려난다.
     */
    private List<FewShotExample> load(Long businessId) {
        int size = properties.fewShotSize();
        if (size <= 0) {
            return List.of();
        }
        List<FewShotExample> rows = repository.findFewShotExamples(
                businessId, PageRequest.of(0, size * OVERFETCH));

        Set<String> seen = new HashSet<>();
        List<FewShotExample> examples = new ArrayList<>(size);
        for (FewShotExample row : rows) {
            if (examples.size() == size) {
                break;
            }
            // 공백만 있던 적요는 clip 이 null 로 만든다. 워커 스키마가 min_length=1 이라
            // 그대로 보내면 요청 전체가 422 다.
            if (row.description() == null) {
                continue;
            }
            // 분류 코드에는 공백이 없으므로 앞에 두면 짝의 경계가 모호하지 않다.
            if (seen.add(row.category() + " " + row.description())) {
                examples.add(row);
            }
        }
        return List.copyOf(examples);
    }

    /** 어느 날짜의 목록인지 함께 들고 있어야 자정을 넘겼는지 알 수 있다. */
    private record Snapshot(LocalDate day, List<FewShotExample> examples) {
    }
}
