package com.seatsac.common.web;

import com.seatsac.common.web.client.InternalRestClientFactory;
import com.seatsac.common.web.error.ConstraintViolationExceptionHandler;
import com.seatsac.common.web.error.ErrorResponder;
import com.seatsac.common.web.error.GlobalExceptionHandler;
import com.seatsac.common.web.trace.TraceIdFilter;
import com.seatsac.common.web.user.CurrentUserArgumentResolver;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 의존성만 추가하면 켜진다. 끄려면 {@code app.common.web.enabled=false}
 * <ul>
 *   <li>TraceIdFilter: X-Trace-Id 검증·생성(소문자 hex 32자리), MDC, 응답 헤더</li>
 *   <li>GlobalExceptionHandler: 모든 예외 → ErrorResponse (로그·MDC는 ErrorResponder)</li>
 *   <li>&#64;CurrentUser: X-User-Id · X-User-Role 헤더 → String userId 또는 AuthUser 인자</li>
 *   <li>InternalRestClientFactory: 내부 호출용 RestClient (1s/3s, traceId 전파, 5xx·타임아웃 → 503)</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "app.common.web", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CommonWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ErrorResponder errorResponder() {
        return new ErrorResponder();
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler(ErrorResponder responder) {
        return new GlobalExceptionHandler(responder);
    }

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
    @ConditionalOnMissingBean
    public InternalRestClientFactory internalRestClientFactory() {
        return new InternalRestClientFactory();
    }

    /** jakarta.validation을 쓰는 서비스에서만 등록 ({@code @Validated} 파라미터 검증 실패 → 400) */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "jakarta.validation.ConstraintViolationException")
    static class ValidationErrorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ConstraintViolationExceptionHandler constraintViolationExceptionHandler(ErrorResponder responder) {
            return new ConstraintViolationExceptionHandler(responder);
        }
    }
}
