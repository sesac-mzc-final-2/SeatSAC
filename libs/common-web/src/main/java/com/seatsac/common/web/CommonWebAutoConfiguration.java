package com.seatsac.common.web;

import com.seatsac.common.web.client.InternalRestClientFactory;
import com.seatsac.common.web.error.GlobalExceptionHandler;
import com.seatsac.common.web.trace.TraceIdFilter;
import com.seatsac.common.web.user.CurrentUserArgumentResolver;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 의존성만 추가하면 켜진다. 끄려면 app.common.web.enabled=false
 * - TraceIdFilter: X-Trace-Id 읽기·생성, MDC, 응답 헤더
 * - GlobalExceptionHandler: 모든 예외 → ErrorResponse
 * - @CurrentUser: X-User-Id · X-User-Role 헤더 → 메서드 인자
 * - InternalRestClientFactory: 내부 호출용 RestClient (타임아웃 1s/3s, traceId 전달)
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "app.common.web", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import(GlobalExceptionHandler.class)
public class CommonWebAutoConfiguration {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> bean = new FilterRegistrationBean<>(new TraceIdFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    public WebMvcConfigurer currentUserWebMvcConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new CurrentUserArgumentResolver());
            }
        };
    }

    @Bean
    public InternalRestClientFactory internalRestClientFactory() {
        return new InternalRestClientFactory();
    }
}
