package com.jangoyebo.core;

import java.util.Map;
import java.util.Optional;

/**
 * 거래 카테고리 조회 — {@code core} 모듈이 밖에 내놓는 공개 API.
 *
 * <p><b>왜 이 자리에 있는가.</b> Modulith 는 모듈의 <b>기저 패키지 바로 아래 public 타입</b>만
 * 밖에서 쓸 수 있게 한다. {@code core.category.TransactionCategoryRepository} 를 직접 부르면
 * 하위 패키지 접근이라 {@code ModularityTests} 가 실패한다. {@code core} 의 package-info 가
 * "Repository 를 모듈 밖으로 노출하지 말고 서비스 인터페이스로만 통하게 한다"고 정해 둔 그 지점이다.
 *
 * <p>{@code forecast} 가 예정 거래를 만들 때 카테고리 <b>코드</b>를 받아 {@code category_id} 로
 * 바꿔야 해서 생겼다. 첫 모듈 간 교차다.
 *
 * <p>23행짜리 고정 시드라 호출마다 조회해도 부담이 없다. 부하가 보이면 구현체에 캐시를 붙인다.
 */
public interface CategoryCatalog {

    /** 활성 카테고리 코드 → id. 없으면 비어 있다. */
    Optional<Long> idByCode(String code);

    /** id → 코드. 응답을 만들 때 역방향으로 쓴다. */
    Map<Long, String> codeById();
}
