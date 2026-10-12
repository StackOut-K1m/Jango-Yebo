package com.jangoyebo.core.fin;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.security.FieldEncryptor;
import com.jangoyebo.core.user.User;
import com.jangoyebo.core.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@code userKey} 확보 검증 ({@code MEMBER_01} / {@code MEMBER_02}).
 *
 * <p>DB 와 암호화는 진짜를 쓰고 금융망만 대역으로 바꾼다 — <b>암호화해서 저장하는지</b>가
 * 검증 대상의 절반이라 {@link FieldEncryptor} 를 대역으로 바꾸면 볼 것이 없어진다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
class FinUserKeyServiceTests {

    private static final String CREATE = FinTestFixtures.BASE_URL + "/ssafy/api/v1/member/";
    private static final String SEARCH = FinTestFixtures.BASE_URL + "/ssafy/api/v1/member/search";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FieldEncryptor encryptor;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private MockRestServiceServer server;
    private FinUserKeyService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(FinTestFixtures.BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        service = new FinUserKeyService(
                new FinOpenApiClient(builder.build(),
                        new FinApiHeaderFactory(FinTestFixtures.properties(), Clock.systemUTC()),
                        JsonMapper.builder().build()),
                userRepository, encryptor);
    }

    /**
     * 서비스를 손수 조립하므로 Spring 프록시가 없다 — {@code @Transactional} 이 걸리지 않아
     * 더티 체킹이 일어나지 않는다. 운영에서는 빈이 프록시되므로, 테스트가 그 트랜잭션을 대신 연다.
     */
    private String resolveInTransaction(Long userId) {
        return new TransactionTemplate(transactionManager).execute(status -> service.resolve(userId));
    }

    private User newUser() {
        long n = ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999);
        return userRepository.save(User.signUp("u" + n + "@jango.test", "hash", "테스트", null));
    }

    /** 회원 계열 응답은 평평하고 {@code responseCode} 가 없다. 201 을 성공으로 읽어야 한다. */
    @Test
    void issuesAndStoresEncryptedUserKey() {
        User user = newUser();
        server.expect(requestTo(CREATE))
                .andExpect(jsonPath("$.apiKey").value("test-api-key"))
                .andExpect(jsonPath("$.userId").value(user.getEmail()))
                .andRespond(withStatus(HttpStatus.CREATED).body("""
                        { "userId": "x", "userName": "x", "userKey": "issued-key" }
                        """).contentType(MediaType.APPLICATION_JSON));

        assertThat(resolveInTransaction(user.getId())).isEqualTo("issued-key");

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getFinUserKey())
                .as("계좌 접근권이라 평문으로 두지 않는다")
                .isNotNull()
                .isNotEqualTo("issued-key");
        assertThat(encryptor.decrypt(reloaded.getFinUserKey())).isEqualTo("issued-key");
        server.verify();
    }

    /** 이미 있으면 금융망을 부르지 않는다 — {@code once()} 가 아니라 아예 기대가 없다. */
    @Test
    void reusesStoredKeyWithoutCallingFinNetwork() {
        User user = newUser();
        user.linkFinUserKey(encryptor.encrypt("stored-key"));
        userRepository.save(user);

        assertThat(resolveInTransaction(user.getId())).isEqualTo("stored-key");
        server.verify();
    }

    /**
     * {@code E4002}(이미 존재하는 ID)는 우리가 이전 시도에서 만들어 두고 저장에 실패한 경우일 수
     * 있다. ERD 가 "계좌 연결 시 재시도"를 설계했으므로 <b>조회로 복구</b>한다.
     */
    @Test
    void recoversWithSearchWhenEmailIsAlreadyTaken() {
        User user = newUser();
        server.expect(requestTo(CREATE))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("""
                        { "responseCode": "E4002", "responseMessage": "이미 존재하는 ID입니다." }
                        """).contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(SEARCH))
                .andRespond(withStatus(HttpStatus.CREATED).body("""
                        { "userId": "x", "userKey": "recovered-key" }
                        """).contentType(MediaType.APPLICATION_JSON));

        assertThat(resolveInTransaction(user.getId())).isEqualTo("recovered-key");
        server.verify();
    }

    /** {@code E4002} 가 아닌 실패는 조회로 넘어가지 않는다 — 다음 호출도 같은 이유로 실패한다. */
    @Test
    void doesNotFallBackToSearchOnOtherFailures() {
        User user = newUser();
        server.expect(ExpectedCount.once(), requestTo(CREATE))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("""
                        { "responseCode": "H1003", "responseMessage": "전송일시가 유효하지 않습니다." }
                        """).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> resolveInTransaction(user.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FIN_API_FAILED);
        server.verify();
    }

    /** {@code userKey} 없는 응답을 통과시키면 null 을 암호화해 저장하고 나중에 터진다. */
    @Test
    void refusesResponseWithoutUserKey() {
        User user = newUser();
        server.expect(requestTo(CREATE))
                .andRespond(withStatus(HttpStatus.CREATED).body("""
                        { "userId": "x", "userName": "x" }
                        """).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> resolveInTransaction(user.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("userKey");

        assertThat(userRepository.findById(user.getId()).orElseThrow().getFinUserKey()).isNull();
    }
}
