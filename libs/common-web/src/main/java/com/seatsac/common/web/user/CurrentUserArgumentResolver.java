package com.seatsac.common.web.user;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.ErrorCode;
import com.seatsac.common.core.http.Headers;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** X-User-Id · X-User-Role 헤더 → CurrentUser. 인증이 필요한 경로인데 헤더가 없으면 401 (Gateway 우회 방어). */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter p) {
        return CurrentUser.class.equals(p.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter p, ModelAndViewContainer mav, NativeWebRequest req,
                                  WebDataBinderFactory binder) {
        String userId = req.getHeader(Headers.USER_ID);
        if (userId == null || userId.isBlank()) {
            if (p.isOptional()) {
                return null;
            }
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "인증 정보가 없습니다.");
        }
        String role = req.getHeader(Headers.USER_ROLE);
        return new CurrentUser(userId, role == null ? Headers.ROLE_USER : role);
    }
}
