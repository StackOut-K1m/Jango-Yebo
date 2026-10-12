package com.jangoyebo.common.entity;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** {@link BaseEntity}의 {@code @CreatedDate}·{@code @LastModifiedDate}를 활성화한다. */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
