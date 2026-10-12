package com.jangoyebo.common;

import com.jangoyebo.common.tenancy.CurrentBusinessArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** {@code @CurrentBusiness} 해석기 등록. */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentBusinessArgumentResolver currentBusinessResolver;

    public WebMvcConfig(CurrentBusinessArgumentResolver currentBusinessResolver) {
        this.currentBusinessResolver = currentBusinessResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentBusinessResolver);
    }
}
