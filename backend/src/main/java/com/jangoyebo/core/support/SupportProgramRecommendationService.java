package com.jangoyebo.core.support;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.core.CashGapOutlook;
import com.jangoyebo.core.SupportProgramSuggestions;
import com.jangoyebo.core.business.Business;
import com.jangoyebo.core.business.BusinessRepository;
import com.jangoyebo.core.support.SupportProgramRecommendationDtos.CashGap;
import com.jangoyebo.core.support.SupportProgramRecommendationDtos.Item;
import com.jangoyebo.core.support.SupportProgramRecommendationDtos.Profile;
import com.jangoyebo.core.support.SupportProgramRecommendationDtos.Recommendations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 맞춤·공백 연계 추천 (API 17.3, P1-15).
 *
 * <h2>자격을 판정하지 않는다</h2>
 *
 * <p>여기서 내는 것은 「조건에 맞을 가능성」이지 「대상이다」가 아니다. 17.1 검색과 같은
 * 태도이며, 최종 판단은 주관 기관이 한다(응답의 {@code disclaimer} 가 고정 노출된다).
 *
 * <h2>모르면 남긴다</h2>
 *
 * <p>공고가 지역·업종을 안 적었으면 <b>후보에서 빼지 않는다.</b> 17.1 검색이 이미 그
 * 태도이고({@code SupportProgramSearchRepository}), 여기서 뒤집으면 <b>같은 공고가 검색에는
 * 나오고 추천에는 안 나오는</b> 어긋남이 생긴다. 대신 안 적힌 조건은 점수의 분모에서도
 * 빠진다 — 말하지 않은 것으로 감점하지 않는다.
 */
@Service
public class SupportProgramRecommendationService implements SupportProgramSuggestions {

    private static final Logger log =
            LoggerFactory.getLogger(SupportProgramRecommendationService.class);

    /** 공백을 볼 창. 10.1 예측 기본과 같은 30일이다. */
    private static final int GAP_WINDOW_DAYS = 30;

    /** 한 번에 보여 줄 추천 수. 화면이 스크롤 없이 훑을 만큼만 낸다. */
    private static final int MAX_ITEMS = 20;

    /** 후보로 훑을 상한. 마감 임박순이라 앞쪽이 쓸모 있는 것들이다. */
    private static final int CANDIDATE_LIMIT = 300;

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final BusinessGuard guard;
    private final BusinessRepository businesses;
    private final SupportProgramSearchRepository search;
    private final CashGapOutlook cashGaps;

    public SupportProgramRecommendationService(BusinessGuard guard,
                                               BusinessRepository businesses,
                                               SupportProgramSearchRepository search,
                                               CashGapOutlook cashGaps) {
        this.guard = guard;
        this.businesses = businesses;
        this.search = search;
        this.cashGaps = cashGaps;
    }

    @Transactional(readOnly = true)
    public Recommendations recommend(Long businessId, boolean cashGapLinked) {
        Business business = businesses.findById(businessId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_NOT_FOUND));
        guard.assertOwned(business);

        Profile profile = profileOf(business);
        Optional<CashGapOutlook.NearestGap> gap = cashGapLinked
                ? cashGaps.nearestGap(businessId, GAP_WINDOW_DAYS)
                : Optional.empty();
        LocalDate today = LocalDate.now(SEOUL);

        List<Item> items = rank(business, profile,
                gap.map(CashGapOutlook.NearestGap::firstShortfallDate).orElse(null), today);

        Instant baseTime = gap.map(CashGapOutlook.NearestGap::baseTime).orElse(Instant.now());
        CashGap cashGap = gap
                .map(g -> new CashGap(g.firstShortfallDate(), g.maxShortfallAmount()))
                .orElse(null);
        return new Recommendations(baseTime, profile, cashGap, items,
                SupportProgramRecommendationDtos.DISCLAIMER);
    }

    /**
     * 후보를 뽑아 점수를 매기고 자른다 — <b>17.3 추천과 공백 제안이 같은 답을 내는 자리다.</b>
     *
     * <p>두 경로가 각자 뽑으면 「지원 제도 3건 확인」을 누른 사장님이 5건을 보게 된다.
     * 한 곳에만 두는 이유가 그것이고, 후보를 17.1 검색으로 뽑는 이유도 같다.
     */
    private List<Item> rank(Business business, Profile profile,
                            LocalDate firstShortfallDate, LocalDate today) {
        // 후보는 검색과 같은 경로로 뽑는다 — 「모르면 남긴다」 규칙이 한 곳에만 있어야
        // 두 화면이 같은 공고를 보여 준다.
        var page = search.search(null, blankToNull(business.getRegion()),
                broadestRegion(business.getRegion()),
                business.getIndustryCode(), null, today, false, "DEADLINE",
                PageRequest.of(0, CANDIDATE_LIMIT));

        List<Item> items = new ArrayList<>();
        for (SupportProgram program : page.getContent()) {
            items.add(score(program, business, profile, firstShortfallDate, today));
        }
        // 점수 내림차순, 같으면 마감이 임박한 쪽. **동점에서 순서가 흔들리면** 화면을
        // 새로고침할 때마다 추천이 바뀌어 사장님이 본 것을 다시 못 찾는다.
        items.sort((a, b) -> {
            int byScore = Double.compare(b.matchScore(), a.matchScore());
            if (byScore != 0) {
                return byScore;
            }
            if (a.applyEnd() == null || b.applyEnd() == null) {
                return a.applyEnd() == null ? (b.applyEnd() == null ? 0 : 1) : -1;
            }
            return a.applyEnd().compareTo(b.applyEnd());
        });
        return items.size() > MAX_ITEMS ? new ArrayList<>(items.subList(0, MAX_ITEMS)) : items;
    }

    /**
     * 공백에 붙일 지원 제도 건수 (API 10.2 {@code suggestions}).
     *
     * <h2>예외를 밖으로 내보내지 않는다</h2>
     *
     * <p>이 메서드는 <b>10.2 정산 공백 조회(P0-08)의 트랜잭션 안에서</b> 불린다. 여기서
     * 던지면 바깥이 rollback-only 로 표시돼 <b>지원 제도가 고장났다는 이유로 공백 조회
     * 전체가 500</b> 이 된다. 공백은 P0 이고 지원 제도는 P1 이다 — 곁다리가 본체를
     * 넘어뜨리게 둘 수 없다.
     *
     * <p>그래서 <b>이 안에서 잡는다.</b> 바깥에서 잡으면 늦는다 — 트랜잭션 인터셉터가
     * 예외를 이미 본 뒤라 표시가 남는다. 같은 함정을 {@code CashGapOutlookService} 에서
     * 한 번 밟았고 테스트 일곱 개가 한꺼번에 500 을 냈다.
     *
     * <h2>⚠ 이 catch 가 막지 <b>못하는</b> 것</h2>
     *
     * <p><b>SQL 에서 나는 실패는 못 막는다.</b> 쿼리가 터지면 Postgres 가 트랜잭션을 통째로
     * 중단시키고, 그 뒤에는 예외를 잡아도 커밋이 실패한다. 잡는 것과 트랜잭션이 사는 것은
     * 다른 이야기다.
     *
     * <p>2026-09-17 고장 주입에서 이걸 확인했다. 공고 한 건의 {@code regions} 를 배열 대신
     * 문자열로 바꿨더니 {@code jsonb_array_length} 가
     * {@code cannot get array length of a scalar} 로 터졌고, <b>이 catch 가 실제로 실행됐는데도
     * 10.2 가 500 이었다.</b> 「JSONB 모양이 어긋나도 여기서 막는다」고 적었던 앞선 주석은
     * 틀린 말이었다.
     *
     * <p>그 경우는 이제 <b>SQL 쪽에서</b> 막는다 — {@code core.jsonb_text_array}(V33) 가 값을
     * 먼저 배열로 바꿔 배열 함수가 스칼라를 보지 않게 한다. Java 짝은 {@link Eligibility} 다.
     *
     * <p>여기 남은 역할은 <b>쿼리가 아닌 자리에서 나는 오류</b>다 — 점수 계산·정렬·날짜
     * 처리 같은 것들.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<GapHint> forGap(Long businessId, LocalDate firstShortfallDate) {
        try {
            // 테넌시는 부르는 쪽이 이미 좁혔다 — 10.2 는 @CurrentBusiness 로 인증된
            // 사업장만 받는다. 여기서 guard 를 다시 거는 것은 중복이다.
            Business business = businesses.findById(businessId).orElse(null);
            if (business == null) {
                return Optional.empty();
            }
            List<Item> items = rank(business, profileOf(business), firstShortfallDate,
                    LocalDate.now(SEOUL));
            if (items.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new GapHint(items.size(), items.stream()
                    .anyMatch(item -> Boolean.TRUE.equals(item.canArriveBeforeGap()))));
        } catch (RuntimeException e) {
            log.warn("공백 지원 제도 제안을 만들지 못해 건너뛴다 businessId={}", businessId, e);
            return Optional.empty();
        }
    }

    private Profile profileOf(Business business) {
        return new Profile(business.getRegion(), business.getIndustryCode(),
                business.getAnnualRevenue(), yearsInBusiness(business.getOpenedOn()));
    }

    /** 개업일이 없으면 업력을 모른다. **0 으로 두지 않는다** — 「1년 미만」과 「모름」은 다르다. */
    private Integer yearsInBusiness(LocalDate openedOn) {
        if (openedOn == null) {
            return null;
        }
        long years = ChronoUnit.YEARS.between(openedOn, LocalDate.now(SEOUL));
        return years < 0 ? null : (int) years;
    }

    /**
     * 점수는 <b>맞은 조건 수 ÷ 공고가 실제로 건 조건 수</b>다.
     *
     * <p>공고가 말하지 않은 조건은 분모에서도 빠진다 — 조건을 적게 적은 공고가 그 이유로
     * 불리해지면 안 된다. 그 공고는 조건이 없는 게 아니라 <b>본문에 안 적혀 있을 뿐</b>이고,
     * 실측상 그런 공고가 35% 다.
     *
     * <p><b>조건을 하나도 안 건 공고는 0.0 이 아니라 0.5 다.</b> 0 으로 두면 목록 맨 아래로
     * 밀려 사실상 안 보이는데, 그건 「해당 없음」이 아니라 「모름」이다.
     *
     * <h2>모름은 양쪽 다 뺀다</h2>
     *
     * <p>오래 <b>이 원칙을 공고 쪽에만</b> 적용했다. 사업장이 말하지 않은 것(빈 프로필)은
     * 「안 맞음」으로 처리했고, 그래서 프로필이 빈 사장님에게 <b>조건을 적은 공고가 0.0,
     * 안 적은 공고가 0.5</b> 가 되어 <b>정보가 가장 적은 공고가 맨 위로 올라왔다.</b>
     *
     * <p>같은 「모른다」인데 한쪽은 분모에서 빼 주고 한쪽은 0점을 주고 있었다. 이제 <b>판정할
     * 수 없는 조건은 분자에서도 분모에서도 뺀다.</b> 프로필이 완전히 비면 모든 공고가
     * 0.5 동률이 되고 정렬이 마감 임박순으로 떨어진다 — 「누가 더 맞는지 알 수 없으니
     * 급한 것부터」가 정직한 답이다.
     *
     * <p>다만 <b>「전국」·「전 업종」은 사업장을 몰라도 판정된다.</b> 그 공고는 사업장이
     * 무엇이든 대상이라고 말하고 있으므로 <b>맞은 것으로 센다</b> — 모르는 게 아니다.
     */
    private Item score(SupportProgram program, Business business, Profile profile,
                       LocalDate firstShortfallDate, LocalDate today) {
        Map<String, Object> eligibility = program.getEligibility();
        List<String> matched = new ArrayList<>();
        int stated = 0;

        // 셋 다 「판정 불가면 null」이다. null 은 분자에도 분모에도 안 들어간다.
        Boolean region = regionMatch(
                Eligibility.textList(eligibility, "regions"), business.getRegion());
        if (region != null) {
            stated++;
            if (region) {
                matched.add("지역");
            }
        }
        Boolean industry = industryMatch(
                Eligibility.textList(eligibility, "industries"), business.getIndustryCode());
        if (industry != null) {
            stated++;
            if (industry) {
                matched.add("업종");
            }
        }
        Boolean revenue = revenueMatch(
                eligibility.get("maxAnnualRevenue"), business.getAnnualRevenue());
        if (revenue != null) {
            stated++;
            if (revenue) {
                matched.add("매출 상한");
            }
        }
        Boolean experience = yearsMatch(eligibility.get("minYearsInBusiness"),
                eligibility.get("maxYearsInBusiness"), profile.yearsInBusiness());
        if (experience != null) {
            stated++;
            if (experience) {
                matched.add("업력");
            }
        }

        double score = stated == 0 ? 0.5 : (double) matched.size() / stated;
        return new Item(
                program.getId(),
                program.getTitle(),
                program.getOrganizer(),
                program.getSourceUrl(),
                program.getSupportType(),
                program.getMaxAmount(),
                program.getApplicationEndDate(),
                Math.round(score * 100) / 100.0,
                matched,
                program.needsVerification(),
                program.getExpectedDisbursementDays(),
                arrivesBeforeGap(program, firstShortfallDate),
                timingNote(program, firstShortfallDate, today));
    }

    /**
     * 「공백 전에 돈이 들어오나」.
     *
     * <p><b>모르면 {@code null} 이다.</b> 실측상 공고의 97% 가 지급 시기를 말하지 않는다
     * (32건 중 1건). 모르는 것을 {@code false} 로 두면 「못 받는다」로 읽혀 쓸 수 있는
     * 지원금을 접게 하고, {@code true} 로 두면 오지 않을 돈을 기다리며 다른 대책을 접는다.
     * 둘 다 사장님이 손해를 보는 쪽이라 키를 뺀다.
     */
    private Boolean arrivesBeforeGap(SupportProgram program, LocalDate firstShortfallDate) {
        Integer days = program.getExpectedDisbursementDays();
        if (firstShortfallDate == null || days == null
                || program.getApplicationEndDate() == null) {
            return null;
        }
        // 오늘 신청한다고 보고 역산한다. 마감일 기준으로 재면 「마감에 맞춰 내면 늦는다」를
        // 말하게 되는데, 사장님이 알고 싶은 것은 지금 움직였을 때의 이야기다.
        return LocalDate.now(SEOUL).plusDays(days).isBefore(firstShortfallDate);
    }

    /**
     * 시점에 대해 말할 수 있는 한 줄.
     *
     * <p><b>모를 때도 비우지 않는다.</b> 빈 자리는 화면에서 「문제 없음」으로 읽힌다 —
     * 도메인 규칙 1 이 막는 단정이 침묵의 모양으로 나가는 경우다.
     */
    private String timingNote(SupportProgram program, LocalDate firstShortfallDate,
                              LocalDate today) {
        LocalDate end = program.getApplicationEndDate();
        Integer days = program.getExpectedDisbursementDays();

        if (firstShortfallDate == null) {
            return end == null
                    ? "신청 기간이 공고에 없어 기관 확인이 필요합니다."
                    : "마감 " + end + "까지 신청할 수 있습니다.";
        }
        if (end != null && !end.isBefore(firstShortfallDate)) {
            return "신청 마감(" + end + ")이 예상 공백일(" + firstShortfallDate
                    + ") 이후라 이 공백에는 늦을 수 있습니다.";
        }
        if (days == null) {
            return "지급 시기는 공고에 없어 기관 확인이 필요합니다. 신청 마감은 "
                    + (end == null ? "공고 참조" : end.toString()) + "입니다.";
        }
        LocalDate arrival = today.plusDays(days);
        return arrival.isBefore(firstShortfallDate)
                ? "지금 신청하면 약 " + days + "일 뒤(" + arrival + ") 지급되어 공백일("
                        + firstShortfallDate + ") 이전 수령 가능성이 있습니다."
                : "집행까지 약 " + days + "일이 걸려 공백일(" + firstShortfallDate
                        + ") 이전 수령은 어려울 수 있습니다.";
    }

    /** 「광주 서구」 → 「광주」. 검색(17.1)과 같은 규칙이다. */
    private String broadestRegion(String region) {
        if (region == null || region.isBlank()) {
            return null;
        }
        return region.trim().split("\\s+")[0];
    }

    /**
     * 지역이 맞나. <b>판정할 수 없으면 {@code null} 이고, 그 조건은 분자·분모 양쪽에서 빠진다.</b>
     *
     * <p>{@code null} 이 되는 경우가 둘이다 — <b>공고가 지역을 안 적었을 때</b>와 <b>사업장
     * 주소를 모를 때</b>. 전에는 뒤엣것을 {@code false} 로 두어 프로필이 빈 사장님에게
     * 조건을 적은 공고가 전부 0점이 됐다.
     *
     * <p>「전국」은 예외다 — <b>사업장을 몰라도 대상</b>이라고 공고가 말하고 있으므로 맞은
     * 것으로 센다. 그래서 주소 검사보다 먼저 본다.
     *
     * <p><b>전체 주소와 가장 넓은 토막을 둘 다 본다.</b> 하나로는 안 된다 — 「전북 군산시」를
     * 「전북」으로만 줄이면 「군산시」 공고를 놓치고, 전체 문자열만 쓰면 「전남광주」 같은
     * 묶음 태그를 놓친다. <b>군산시 사장님이 군산시 지원금을 못 보는 일이 실제로 있었다</b>
     * (2026-09-17 실호출). 후보를 뽑는 17.1 검색도 같은 이유로 둘을 받는다.
     */
    private Boolean regionMatch(List<String> regions, String region) {
        if (regions.isEmpty()) {
            return null;                        // 공고가 지역을 안 적었다
        }
        for (String value : regions) {
            if (value != null && value.contains("전국")) {
                return Boolean.TRUE;            // 사업장을 몰라도 대상이다
            }
        }
        if (region == null || region.isBlank()) {
            return null;                        // 사업장 주소를 모른다 — 감점하지 않는다
        }
        String full = region.trim();
        String broadest = broadestRegion(region);
        for (String value : regions) {
            if (value == null) {
                continue;
            }
            // 양방향이다. 공고가 더 넓게 적을 수도(「전북」), 더 좁게 적을 수도(「군산시」) 있다.
            if (value.contains(full) || full.contains(value)) {
                return true;
            }
            if (broadest != null && (value.contains(broadest) || broadest.contains(value))) {
                return true;
            }
        }
        return false;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 업종이 맞나. <b>판정할 수 없으면 {@code null}</b> — {@link #regionMatch} 와 같은 규칙이다.
     *
     * <p>「전 업종」은 사업장 업종을 몰라도 <b>맞은 것</b>이다. 그 공고가 업종을 안 가린다고
     * 말하고 있어서다.
     */
    private Boolean industryMatch(List<String> industries, String industryCode) {
        if (industries.isEmpty()) {
            return null;
        }
        for (String value : industries) {
            if (value != null && (value.contains("전 업종") || value.contains("전업종"))) {
                return Boolean.TRUE;
            }
        }
        if (industryCode == null || industryCode.isBlank()) {
            return null;
        }
        for (String value : industries) {
            if (value != null && (value.contains(industryCode) || industryCode.contains(value))) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    /**
     * 매출 상한을 넘지 않나. <b>공고가 상한을 안 적었거나 사업장 매출을 모르면 {@code null}</b>.
     *
     * <p>매출을 모르는 사장님을 「상한 초과」로 다루면, 매출을 안 적었다는 이유로 조건이
     * 붙은 공고가 전부 0점이 된다. 그건 판정이 아니라 <b>추측으로 내린 감점</b>이다.
     */
    private Boolean revenueMatch(Object cap, BigDecimal annualRevenue) {
        if (!(cap instanceof Number number)) {
            return null;
        }
        if (annualRevenue == null) {
            return null;
        }
        return annualRevenue.compareTo(new BigDecimal(number.toString())) <= 0;
    }

    /** 업력이 범위 안인가. <b>공고가 안 적었거나 개업일을 모르면 {@code null}</b>. */
    private Boolean yearsMatch(Object minYears, Object maxYears, Integer years) {
        if (!(minYears instanceof Number) && !(maxYears instanceof Number)) {
            return null;
        }
        if (years == null) {
            return null;
        }
        return (!(minYears instanceof Number min) || years >= min.intValue())
                && (!(maxYears instanceof Number max) || years <= max.intValue());
    }
}
