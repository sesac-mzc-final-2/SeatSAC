package com.seatsac.common.web.user;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.core.http.Headers;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@code @CurrentUser}가 붙은 {@code String}·{@link AuthUser} 인자에 {@code X-User-Id}·{@code X-User-Role}을
 * 넣는다. 헤더가 없거나 {@code sub}가 UUID 형식이 아니면 401 (Gateway 우회 방어, API 명세서 2장).
 * {@code Optional<...>}로 받으면 헤더가 없을 때 빈 값이 들어간다(인증이 필요 없는 경로).
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private static final Pattern SUB_FORMAT =
        Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Override
    public boolean supportsParameter(MethodParameter p) {
        if (!p.hasParameterAnnotation(CurrentUser.class)) {
            return false;
        }
        Class<?> type = targetType(p);
        return String.class.equals(type) || AuthUser.class.equals(type);
    }

    @Override
    public Object resolveArgument(MethodParameter p, ModelAndViewContainer mav, NativeWebRequest req,
                                  WebDataBinderFactory binder) {
        boolean optional = Optional.class.equals(p.getParameterType()) || p.isOptional();
        String userId = req.getHeader(Headers.USER_ID);

        if (userId == null || userId.isBlank()) {
            if (optional) {
                return empty(p);
            }
            throw unauthorized("USER_HEADER_MISSING");
        }
        if (!SUB_FORMAT.matcher(userId).matches()) {
            // 형식이 틀린 값은 선택 인자라도 받지 않는다 (Gateway를 우회한 호출)
            throw unauthorized("USER_HEADER_INVALID");
        }

        Object value = String.class.equals(targetType(p))
            ? userId
            : new AuthUser(userId, role(req));
        return Optional.class.equals(p.getParameterType()) ? Optional.of(value) : value;
    }

    private static Class<?> targetType(MethodParameter p) {
        return Optional.class.equals(p.getParameterType())
            ? p.nested().getNestedParameterType()
            : p.getParameterType();
    }

    private static Object empty(MethodParameter p) {
        return Optional.class.equals(p.getParameterType()) ? Optional.empty() : null;
    }

    private static String role(NativeWebRequest req) {
        String role = req.getHeader(Headers.USER_ROLE);
        return role == null || role.isBlank() ? Headers.ROLE_USER : role;
    }

    private static BusinessException unauthorized(String reason) {
        return new BusinessException(CommonResponseCode.UNAUTHORIZED, "인증 정보가 없습니다.",
            List.of(ErrorDetail.of(Headers.USER_ID, null, reason)));
    }
}
