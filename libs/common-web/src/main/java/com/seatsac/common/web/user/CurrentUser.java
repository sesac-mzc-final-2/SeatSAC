package com.seatsac.common.web.user;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gateway가 넣어 준 사용자 정보를 컨트롤러 인자로 받는다 — API 명세서 2장.
 *
 * <pre>
 * public Dto hold(&#64;CurrentUser String userId, &#64;RequestBody Req req)   // sub 만 필요할 때
 * public Dto list(&#64;CurrentUser AuthUser user)                            // 역할도 볼 때
 * </pre>
 *
 * {@code X-User-Id}가 없거나 UUID 형식이 아니면 401 {@code UNAUTHORIZED}로 막는다(Gateway 우회 방어).
 * 인증이 필요 없는 경로에서 선택으로 받으려면 {@code Optional<String>}·{@code Optional<AuthUser>}로 받는다.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
