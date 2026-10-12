package com.jangoyebo.core.loan;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.core.fin.LoanClient.LoanProductRec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 금융망 대출 상품 동기화 (API 16.3, P1-14).
 *
 * <p>여기서 지키는 것은 하나다 — <b>동기화가 {@code recommendable} 을 건드리지 않는다.</b>
 * 덮어쓰면 매 동기화마다 남의 시험 상품이 사장님 화면에 되살아난다. 금융망에는 대출 상품
 * 삭제 API 가 없고 팀 {@code apiKey} 를 가진 누구나 상품을 만들 수 있다.
 */
@NeedsDocker
@SpringBootTest
class LoanProductSyncServiceTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LoanProductSyncService sync;

    private static LoanProductRec 상품(String externalId, String name, String rate) {
        return new LoanProductRec(externalId, "999", "싸피은행", name, "설명",
                "C", "신용대출", "365", "1000000", "50000000", rate, "원리금균등상환");
    }

    private String 고유번호() {
        return "P" + System.nanoTime();
    }

    @Test
    void 새_상품은_꺼진_채로_들어온다() {
        String id = 고유번호();

        assertThat(sync.sync(List.of(상품(id, "새 상품", "8.400")))).isEqualTo(1);

        assertThat(켜졌나(id)).as("사람이 켜기 전에는 추천에 안 나간다").isFalse();
    }

    @Test
    void 사람이_켠_것은_다시_동기화해도_켜진_채로_남는다() {
        // 이 클래스의 존재 이유다. 덮어쓰면 매번 꺼져서 켠 사람 일이 헛수고가 된다.
        String id = 고유번호();
        sync.sync(List.of(상품(id, "켤 상품", "8.400")));
        jdbc.update("UPDATE core.loan_product SET recommendable = TRUE WHERE external_id = ?", id);

        sync.sync(List.of(상품(id, "켤 상품", "8.400")));

        assertThat(켜졌나(id)).isTrue();
    }

    @Test
    void 사람이_끈_이유도_지워지지_않는다() {
        String id = 고유번호();
        sync.sync(List.of(상품(id, "뺄 상품", "8.400")));
        jdbc.update("""
                UPDATE core.loan_product SET excluded_reason = '팀원 시험 상품'
                 WHERE external_id = ?
                """, id);

        sync.sync(List.of(상품(id, "뺄 상품", "8.400")));

        assertThat(jdbc.queryForObject("""
                SELECT excluded_reason FROM core.loan_product WHERE external_id = ?
                """, String.class, id))
                .as("null 이면 「아직 검토 안 함」과 구별이 안 된다")
                .isEqualTo("팀원 시험 상품");
    }

    @Test
    void 금리와_이름이_바뀌면_따라간다() {
        // 켜짐 여부만 빼고 나머지는 금융망이 맞다.
        String id = 고유번호();
        sync.sync(List.of(상품(id, "옛 이름", "8.400")));

        sync.sync(List.of(상품(id, "새 이름", "9.900")));

        assertThat(jdbc.queryForObject("""
                SELECT name FROM core.loan_product WHERE external_id = ?
                """, String.class, id)).isEqualTo("새 이름");
        assertThat(jdbc.queryForObject("""
                SELECT interest_rate FROM core.loan_product WHERE external_id = ?
                """, java.math.BigDecimal.class, id)).isEqualByComparingTo("9.900");
    }

    @Test
    void 고유번호나_등급이_없으면_건너뛴다() {
        LoanProductRec 고유번호없음 = new LoanProductRec(null, "999", "싸피은행", "이름", null,
                "C", "신용대출", "365", "1000", "50000", "8.4", "원리금균등상환");
        LoanProductRec 등급없음 = new LoanProductRec(고유번호(), "999", "싸피은행", "이름", null,
                null, "신용대출", "365", "1000", "50000", "8.4", "원리금균등상환");

        assertThat(sync.sync(List.of(고유번호없음, 등급없음))).isZero();
    }

    private boolean 켜졌나(String externalId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT recommendable FROM core.loan_product WHERE external_id = ?
                """, Boolean.class, externalId));
    }
}
