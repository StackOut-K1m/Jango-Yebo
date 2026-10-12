package com.jangoyebo.core.fin;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 은행 목록 (API 7.2). 금융망 {@code inquireBankCodes} 를 그대로 옮긴다.
 *
 * <p><b>캐시가 선택이 아니다.</b> {@code GET /bank-codes} 는 인증이 필요 없는 경로라
 * (공통규약 2.1) 캐시가 없으면 <b>누구든 우리 서버를 금융망으로 향하는 증폭기로 쓸 수 있다.</b>
 * 온보딩 화면을 새로고침할 때마다 왕복이 한 번 늘기도 한다.
 *
 * <p>만료를 두지 않는다. 은행코드는 시연 기간에 바뀌지 않는 참조 데이터이고, 바뀐다 해도
 * 재기동이 곧 갱신이다. TTL 을 넣으면 "만료 직후 금융망이 죽어 있으면 온보딩이 멈춘다"는
 * 창을 스스로 만드는 셈이다.
 *
 * <p><b>실패와 빈 목록은 캐시하지 않는다.</b> 한 번의 실패가 재기동 전까지 굳어 버리면
 * 금융망이 돌아와도 화면이 계속 비어 있다.
 */
@Service
public class BankCodeService {

    private static final Logger log = LoggerFactory.getLogger(BankCodeService.class);

    /** 실측으로 확인한 경로 구간 (2026-09-08). {@code /ssafy/api/v1/edu/bank/inquireBankCodes} */
    private static final String DOMAIN = "bank";
    private static final String API_NAME = "inquireBankCodes";

    private final FinOpenApiClient client;
    private final AtomicReference<List<BankCode>> cached = new AtomicReference<>();

    public BankCodeService(FinOpenApiClient client) {
        this.client = client;
    }

    /**
     * 콜드 스타트에 두 스레드가 함께 들어오면 금융망을 두 번 부른다. 잠그지 않는 쪽을 골랐다 —
     * 부작용 없는 조회이고, 락을 걸면 금융망이 느릴 때 <b>모든</b> 온보딩 요청이 한 줄로 선다.
     *
     * @throws BusinessException {@code 502 FIN_API_FAILED}. 프론트는 재시도를 안내한다
     *                           (화면구성도 SC-01-4 의 금융망 장애 분기)
     */
    public List<BankCode> findAll() {
        List<BankCode> hit = cached.get();
        if (hit != null) {
            return hit;
        }

        List<BankCode> fetched;
        try {
            // 사용자별 데이터가 없는 조회라 빈 userKey 로도 통한다(실측).
            // 이 경로에는 사용자 맥락 자체가 없다 — 인증 없는 엔드포인트다.
            fetched = client.callEduForList(DOMAIN, API_NAME, null, Map.of(), BankCode.class);
        } catch (FinApiException e) {
            // ErrorCode 가 502 에 원인 코드를 함께 싣는 생성자를 이미 갖고 있다.
            // 금융망 코드를 응답 detail 로 내보내면 프론트 이슈에 코드가 함께 붙는다.
            throw new BusinessException(ErrorCode.FIN_API_FAILED, "은행 목록을 가져오지 못했습니다.",
                    e.finCode() == null ? null : Map.of("finCode", e.finCode()));
        }

        if (fetched.isEmpty()) {
            // 캐시하면 재기동 전까지 굳는다. 빈 목록으로는 계좌를 연결할 수 없으니 실패로 본다.
            throw new BusinessException(ErrorCode.FIN_API_FAILED, "은행 목록이 비어 있습니다.");
        }

        cached.set(fetched);
        log.info("은행 목록 {}건 적재", fetched.size());
        return fetched;
    }
}
