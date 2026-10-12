package com.jangoyebo.common.persistence;

import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.format.FormatMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * JSONB 컬럼을 <b>애플리케이션과 같은 Jackson</b> 으로 직렬화한다.
 *
 * <p><b>왜 필요한가.</b> 이 프로젝트는 Jackson 3(`tools.jackson`)를 쓰는데 Hibernate 는 자체
 * 기본 매퍼로 Jackson 2(`com.fasterxml.jackson`)를 쓴다. 그리고 <b>Jackson 2 의 JSR-310 모듈이
 * 클래스패스에 없다</b> — Spring Boot 4 가 Jackson 3 을 쓰므로 그럴 이유가 없었다. 그래서
 * JSONB 에 {@code LocalDate} 가 든 타입을 저장하면 이렇게 터진다:
 *
 * <pre>
 * Java 8 date/time type `java.time.LocalDate` not supported by default:
 * add Module "com.fasterxml.jackson.datatype:jackson-datatype-jsr310"
 * </pre>
 *
 * <p>{@code Map<String,Object>} 로만 쓰던 동안에는 값이 이미 문자열이라 드러나지 않았고,
 * 예측 실행({@code FORECAST_RUN.basis})이 날짜가 든 타입을 처음 저장하면서 나왔다.
 *
 * <p><b>왜 Jackson 2 의 JSR-310 모듈을 넣지 않았는가.</b> 새 의존성을 더하면 <b>같은 앱 안에
 * 날짜를 다루는 Jackson 이 둘</b>이 되고, DB 에 쓰는 표기와 API 응답 표기가 조용히 갈릴 수 있다.
 * 여기 두 메서드를 위임하면 <b>DB JSONB 와 API 응답이 같은 매퍼를 쓴다</b> — 규약도 하나다.
 *
 * <p>Spring 이 Hibernate 에 넘겨 준다 — {@link HibernateJsonConfig}.
 */
public class JacksonJsonFormatMapper implements FormatMapper {

    private final ObjectMapper objectMapper;

    public JacksonJsonFormatMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> T fromString(CharSequence charSequence, JavaType<T> javaType, WrapperOptions options) {
        if (javaType.getJavaTypeClass() == String.class) {
            return javaType.fromString(charSequence);
        }
        return objectMapper.readValue(charSequence.toString(),
                objectMapper.constructType(javaType.getJavaType()));
    }

    @Override
    public <T> String toString(T value, JavaType<T> javaType, WrapperOptions options) {
        if (javaType.getJavaTypeClass() == String.class) {
            return javaType.toString(value);
        }
        return objectMapper.writeValueAsString(value);
    }
}
