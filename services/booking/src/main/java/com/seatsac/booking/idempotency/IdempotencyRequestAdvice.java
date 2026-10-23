package com.seatsac.booking.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.core.http.Headers;
import com.seatsac.common.web.user.CurrentUserHeaders;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Type;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice;

/**
 * BKG-11 멱등 처리의 입구 — {@link Idempotent}가 붙은 핸들러의 요청 본문을 읽은 직후에 끼어든다.
 *
 * <p>본문이 객체로 바뀐 뒤라서 해시는 <b>다시 직렬화한 바이트</b>로 만든다. 공백·필드 순서가 달라도
 * 같은 요청으로 보기 때문이다. 다만 {@code seatIds}의 순서가 다르면 다른 요청이다(화면은 재시도 때
 * 같은 본문을 그대로 보낸다).
 *
 * <p>판정 결과는 셋 중 하나다 — 처리권을 잡으면 요청 속성에 남겨 두고 그대로 진행,
 * 같은 키·다른 본문이면 409 {@code IDEMPOTENCY_KEY_CONFLICT}, 처리 중이면 409
 * {@code IDEMPOTENCY_IN_PROGRESS}, 끝난 요청이면 {@link IdempotentReplayException}으로
 * 저장된 첫 응답을 돌려준다.
 */
@ControllerAdvice
@RequiredArgsConstructor
public class IdempotencyRequestAdvice implements RequestBodyAdvice {

    /** 처리권을 잡은 요청에만 붙는다. 응답 저장({@link IdempotencyResponseAdvice})이 이것을 본다 */
    static final String PENDING_ATTRIBUTE = IdempotencyRequestAdvice.class.getName() + ".PENDING";

    private final IdempotencyStore store;
    private final ObjectMapper mapper;

    /** 처리권을 잡은 요청의 키·해시·처리권(owner) */
    record Pending(String userId, String key, String requestHash, String owner) {
    }

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return parameter.hasMethodAnnotation(Idempotent.class);
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter,
                                           Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) {
        return inputMessage;
    }

    @Override
    public Object handleEmptyBody(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                  Type targetType,
                                  Class<? extends HttpMessageConverter<?>> converterType) {
        return body;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                Type targetType,
                                Class<? extends HttpMessageConverter<?>> converterType) {
        Idempotent idempotent = parameter.getMethodAnnotation(Idempotent.class);
        HttpServletRequest request = currentRequest();
        if (idempotent == null || request == null) {
            return body;
        }

        String key = request.getHeader(Headers.IDEMPOTENCY_KEY);
        if (!StringUtils.hasText(key)) {
            if (idempotent.required()) {
                throw BookingErrorCode.IDEMPOTENCY_KEY_REQUIRED.exception(
                    ErrorDetail.required(Headers.IDEMPOTENCY_KEY));
            }
            return body;
        }

        // 사용자 식별은 공통 규칙(@CurrentUser와 같은 곳)으로 읽는다. 헤더가 없거나 형식이
        // 틀리면 키를 잡지 않고 넘긴다 — 바로 뒤 @CurrentUser 해석이 401을 낸다
        String userId = CurrentUserHeaders.userId(request.getHeader(Headers.USER_ID)).orElse(null);
        if (userId == null) {
            return body;
        }

        String requestHash = store.hash(serialize(body));
        IdempotencyStore.Begin begin = store.begin(userId, key, requestHash);
        if (begin.acquired()) {
            request.setAttribute(PENDING_ATTRIBUTE,
                new Pending(userId, key, requestHash, begin.owner()));
            return body;
        }

        IdempotencyRecord record = begin.existingRecord().orElseThrow();
        if (!requestHash.equals(record.requestHash())) {
            throw BookingErrorCode.IDEMPOTENCY_KEY_CONFLICT.exception(
                ErrorDetail.of(Headers.IDEMPOTENCY_KEY, key, "DIFFERENT_REQUEST"));
        }
        if (!record.isReplayable()) {
            throw BookingErrorCode.IDEMPOTENCY_IN_PROGRESS.exception(
                ErrorDetail.of(Headers.IDEMPOTENCY_KEY, key, IdempotencyRecord.IN_PROGRESS));
        }
        throw new IdempotentReplayException(record.httpStatus(), record.body());
    }

    private byte[] serialize(Object body) {
        try {
            return mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("요청 본문을 해시할 수 없다", e);
        }
    }

    private HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
            ? attributes.getRequest() : null;
    }
}
