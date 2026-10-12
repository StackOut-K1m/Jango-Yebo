package com.jangoyebo.core.fin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.security.FieldEncryptor;
import com.jangoyebo.core.user.User;
import com.jangoyebo.core.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 금융망 {@code userKey} 확보 ({@code MEMBER_01} / {@code MEMBER_02}).
 *
 * <p><b>가입 시점에 발급하지 않는다.</b> 가입 흐름을 외부 API 가용성에 묶지 않기 위해서이며,
 * ERD 가 {@code USER.fin_user_key} 를 nullable + "계좌 연결 시 재시도"로 설계한 방향과 같다.
 * 그래서 이 서비스는 <b>계좌를 연결하는 순간 처음 불린다.</b>
 *
 * <p>{@code userKey} 는 사실상 계좌 접근권이라 암호화해 저장하고 로그에 남기지 않는다.
 */
@Service
public class FinUserKeyService {

    private static final Logger log = LoggerFactory.getLogger(FinUserKeyService.class);

    /** 실측 경로 (2026-09-08). 생성은 {@code /member/}, 조회는 {@code /member/search} 다. */
    private static final String CREATE = "";
    private static final String SEARCH = "search";

    private final FinOpenApiClient client;
    private final UserRepository userRepository;
    private final FieldEncryptor encryptor;

    public FinUserKeyService(FinOpenApiClient client, UserRepository userRepository,
                             FieldEncryptor encryptor) {
        this.client = client;
        this.userRepository = userRepository;
        this.encryptor = encryptor;
    }

    /**
     * 이 사용자의 {@code userKey} 를 돌려준다. 없으면 발급받아 저장한 뒤 돌려준다.
     *
     * <p><b>생성을 먼저 시도하고 중복이면 조회로 넘어간다.</b> 반대 순서(조회 먼저)로 하면
     * <b>신규 사용자마다 반드시 400 을 한 번 맞는다</b> — 대부분이 신규인 흐름에서 손해다.
     *
     * <p>{@code E4002}(이미 존재)는 두 가지를 뜻하는데 <b>구분할 방법이 없다</b> —
     * 우리가 이전 시도에서 만들어 두고 저장에 실패했거나, 다른 팀이 같은 이메일을 먼저 썼거나.
     * ERD 가 재시도를 전제하므로 조회로 복구하되, 후자라면 계좌 목록이 빈 채로 보인다.
     * 그때 사용자에게 필요한 안내는 "다른 이메일을 쓰세요"이고, 그 판단은 이 계층이 못 한다.
     *
     * @throws BusinessException {@code 502 FIN_API_FAILED}
     */
    @Transactional
    public String resolve(Long userId) {
        // 토큰이 가리키는 사용자가 없다 = 삭제된 계정으로 들어왔다는 뜻이다.
        // 새 오류 코드를 만들지 않는다 — BusinessGuard 가 principal 부재를 다루는 방식과 같다.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_TOKEN_INVALID));

        if (user.getFinUserKey() != null) {
            return encryptor.decrypt(user.getFinUserKey());
        }

        String issued = issueOrRecover(user.getEmail());
        user.linkFinUserKey(encryptor.encrypt(issued));
        log.info("사용자 {} 금융망 userKey 확보", userId);
        return issued;
    }

    private String issueOrRecover(String email) {
        try {
            return required(client.callMember(CREATE, Map.of("userId", email), MemberResponse.class));
        } catch (FinApiException e) {
            if (!FinResponseCode.MEMBER_ID_DUPLICATED.equals(e.finCode())) {
                throw new BusinessException(ErrorCode.FIN_API_FAILED, "금융망 계정 생성에 실패했습니다.",
                        e.finCode() == null ? null : Map.of("finCode", e.finCode()));
            }
        }

        try {
            return required(client.callMember(SEARCH, Map.of("userId", email), MemberResponse.class));
        } catch (FinApiException e) {
            throw new BusinessException(ErrorCode.FIN_API_FAILED, "금융망 계정 조회에 실패했습니다.",
                    e.finCode() == null ? null : Map.of("finCode", e.finCode()));
        }
    }

    /** 응답 형태가 바뀌어 {@code userKey} 가 비면 조용히 null 을 저장하게 된다. 여기서 막는다. */
    private static String required(MemberResponse response) {
        if (response == null || response.userKey() == null || response.userKey().isBlank()) {
            throw new BusinessException(ErrorCode.FIN_API_FAILED, "금융망이 userKey 를 주지 않았습니다.");
        }
        return response.userKey();
    }

    /** 회원 계열 응답은 {@code Header} 도 {@code REC} 도 없는 평평한 본문이다(실측). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MemberResponse(String userId, String userName, String userKey) {
    }
}
