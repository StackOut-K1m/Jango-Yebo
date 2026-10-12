package com.jangoyebo.core.loan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.CreditAssets;
import com.jangoyebo.core.fin.FinOpenApiClient;
import com.jangoyebo.core.fin.FinUserKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link CreditAssets} 구현 — {@code LOAN_04}(내 등급)와 {@code LOAN_01}(등급 기준).
 *
 * <h2>등급 기준을 캐시한다</h2>
 *
 * <p>{@code LOAN_01} 은 {@code userKey} 를 쓰지 않는 <b>앱 전역 상수</b>다 — A~E 다섯 줄이고
 * 프로젝트 기간에 바뀌지 않는다. 사용자가 화면을 열 때마다 금융망을 왕복할 이유가 없다.
 * 테이블을 두지 않는 것은 ERD 의 결정이다.
 *
 * <h2>등급은 사업장이 아니라 사람의 것이다</h2>
 *
 * <p>{@code LOAN_04} 는 사업장을 모른다. 사업장 소유자의 {@code userKey} 로 부르므로,
 * <b>가게를 바꿔 조회해도 같은 값</b>이 나온다. 스냅샷은 사업장별로 남기지만 그것은
 * 「이 화면에서 언제 무엇을 봤나」를 남기는 것이지 사업장별 등급이 있다는 뜻이 아니다.
 */
@Service
public class CreditAssetsService implements CreditAssets {

    private static final Logger log = LoggerFactory.getLogger(CreditAssetsService.class);

    private static final String DOMAIN = "loan";
    private static final String MY_RATING = "inquireMyCreditRating";
    private static final String CRITERIA = "inquireAssetBasedCreditRatingList";

    private static final String OWNER_SQL =
            "SELECT owner_user_id FROM core.business WHERE id = ?";

    private static final String INSERT_SNAPSHOT_SQL = """
            INSERT INTO core.credit_snapshot (business_id, grade, demand_deposit_asset,
                                              deposit_savings_asset, total_asset, assessed_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    /** 전역 기준이라 프로세스 수명 동안 한 번만 받는다. */
    private final AtomicReference<List<GradeCriterion>> criteriaCache = new AtomicReference<>();

    private final FinOpenApiClient client;
    private final FinUserKeyService userKeys;
    private final JdbcTemplate jdbc;

    public CreditAssetsService(FinOpenApiClient client, FinUserKeyService userKeys, JdbcTemplate jdbc) {
        this.client = client;
        this.userKeys = userKeys;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Snapshot current(Long businessId) {
        Long ownerId = jdbc.query(OWNER_SQL, rs -> rs.next() ? rs.getLong(1) : null, businessId);
        if (ownerId == null) {
            throw new BusinessException(ErrorCode.BUSINESS_NOT_FOUND);
        }

        MyRatingRec rec = client.callEdu(DOMAIN, MY_RATING, userKeys.resolve(ownerId),
                Map.of(), MyRatingRec.class);
        if (rec == null || rec.ratingName() == null) {
            throw new BusinessException(ErrorCode.FIN_API_FAILED, "신용등급을 조회하지 못했습니다.");
        }

        Snapshot snapshot = new Snapshot(rec.ratingName().trim(),
                won(rec.demandDepositAssetValue()),
                won(rec.depositSavingsAssetValue()),
                won(rec.totalAssetValue()),
                Instant.now());

        jdbc.update(INSERT_SNAPSHOT_SQL, businessId, snapshot.grade(),
                snapshot.demandDeposit(), snapshot.depositSavings(), snapshot.total(),
                java.sql.Timestamp.from(snapshot.assessedAt()));
        return snapshot;
    }

    @Override
    public List<GradeCriterion> criteria() {
        List<GradeCriterion> cached = criteriaCache.get();
        if (cached != null) {
            return cached;
        }
        List<CriterionRec> recs = client.callEduForList(DOMAIN, CRITERIA, null, Map.of(), CriterionRec.class);
        if (recs == null || recs.isEmpty()) {
            throw new BusinessException(ErrorCode.FIN_API_FAILED, "신용등급 기준을 조회하지 못했습니다.");
        }
        // 자산이 큰 등급부터. 화면이 A~E 순으로 보여주고, 등급 판정도 위에서부터 내려온다.
        List<GradeCriterion> criteria = recs.stream()
                .map(r -> new GradeCriterion(r.ratingName().trim(),
                        won(r.minAssetValue()), won(r.maxAssetValue())))
                .sorted(Comparator.comparing(GradeCriterion::minAssetValue).reversed())
                .toList();
        criteriaCache.set(criteria);
        log.info("신용등급 기준 {}개를 받아 캐시했다", criteria.size());
        return criteria;
    }

    /** 금융망은 숫자를 문자열로 준다({@code DemandDepositClient} 와 같은 이유). */
    private static BigDecimal won(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value.trim());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MyRatingRec(String ratingName, String demandDepositAssetValue,
                       String depositSavingsAssetValue, String totalAssetValue) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CriterionRec(String ratingUniqueNo, String ratingName,
                        String minAssetValue, String maxAssetValue) {
    }
}
