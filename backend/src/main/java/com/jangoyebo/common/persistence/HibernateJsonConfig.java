package com.jangoyebo.common.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.hibernate.cfg.MappingSettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Hibernate 의 JSONB 직렬화를 앱 Jackson 으로 바꾼다.
 *
 * <p>이유는 {@link JacksonJsonFormatMapper} 의 javadoc 에 있다. 여기서는 배선만 한다.
 *
 * <p>{@code ObjectMapper} 를 <b>주입받아 파생시킨다</b> — 새로 만들면 날짜 표기·시간대 같은
 * Spring 설정과 갈린다. 다만 <b>null 포함 정책 하나는 되돌린다.</b>
 *
 * <h2>왜 {@code non_null} 을 저장에는 적용하지 않는가</h2>
 *
 * <p>{@code spring.jackson.default-property-inclusion: non_null} 은 <b>응답 규약</b>이다 —
 * 아직 구현되지 않은 필드를 키째 빼서 프론트가 옵셔널로 다루게 한다(구현현황 §2).
 * 그것을 저장에 그대로 쓰면 <b>「값이 null 이다」와 「그 필드가 없다」가 DB 에서 구분되지
 * 않는다.</b> 증빙 추출이 실제로 그 경우다 — 과세분을 못 읽은 것과 애초에 그 항목이 없는
 * 인쇄물은 다른 사실이고, {@code EvidenceExtractionServiceTests} 가 그것을 고정하고 있다.
 *
 * <p>저장은 <b>기록</b>이고 응답은 <b>표현</b>이라 규칙이 다르다.
 */
@Configuration
public class HibernateJsonConfig implements HibernatePropertiesCustomizer {

    private final ObjectMapper objectMapper;

    public HibernateJsonConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.rebuild()
                .changeDefaultPropertyInclusion(inclusion -> JsonInclude.Value.empty()
                        .withValueInclusion(JsonInclude.Include.ALWAYS)
                        .withContentInclusion(JsonInclude.Include.ALWAYS))
                .build();
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(MappingSettings.JSON_FORMAT_MAPPER,
                new JacksonJsonFormatMapper(objectMapper));
    }
}
