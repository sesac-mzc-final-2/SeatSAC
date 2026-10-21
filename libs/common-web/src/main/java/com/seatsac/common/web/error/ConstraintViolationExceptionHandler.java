package com.seatsac.common.web.error;

import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.core.error.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * {@code @Validated} 클래스의 파라미터 검증 실패 → 400 {@code VALIDATION_FAILED}.
 * jakarta.validation이 클래스패스에 있을 때만 등록된다 ({@code CommonWebAutoConfiguration}).
 * 값은 담지 않고 필드 이름만 남긴다.
 */
@RestControllerAdvice
public class ConstraintViolationExceptionHandler {

    private final ErrorResponder responder;

    public ConstraintViolationExceptionHandler(ErrorResponder responder) {
        this.responder = responder;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> constraintViolation(ConstraintViolationException e) {
        List<ErrorDetail> details = e.getConstraintViolations().stream()
            .map(v -> ErrorDetail.of(lastNode(v.getPropertyPath().toString()), null, "INVALID_VALUE"))
            .toList();
        return responder.respond(CommonResponseCode.VALIDATION_FAILED, details, e);
    }

    private static String lastNode(String propertyPath) {
        int dot = propertyPath.lastIndexOf('.');
        return dot < 0 ? propertyPath : propertyPath.substring(dot + 1);
    }
}
