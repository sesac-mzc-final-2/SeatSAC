package com.seatsac.user.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.user.common.UserErrorCode;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cognito {@code GetUser} — 요청의 Access Token으로 email·name을 받는다(2.2.4 v0.3, ADR-54). IAM 서명이 필요 없는
 * API라 SDK 없이 JSON 1.1 프로토콜({@code X-Amz-Target} + {@code application/x-amz-json-1.1})로 직접 부른다.
 *
 * <ul>
 *   <li>Cognito가 토큰을 거절({@code NotAuthorizedException}: scope 누락·폐기)하면 403 {@code TOKEN_REJECTED}</li>
 *   <li>그 밖의 실패(타임아웃·연결·5xx·다른 오류 유형·응답에 sub/email 없음)는 503 {@code IDENTITY_UNAVAILABLE}
 *       — 화면은 잠시 뒤 재시도(계약)</li>
 * </ul>
 * 재시도는 하지 않는다. 토큰·응답 본문은 로그에 남기지 않는다.
 */
@Component
public class CognitoUserInfoClient {

    static final String TARGET_HEADER = "X-Amz-Target";
    static final String GET_USER_TARGET = "AWSCognitoIdentityProviderService.GetUser";
    static final MediaType AMZ_JSON = MediaType.parseMediaType("application/x-amz-json-1.1");
    private static final String NOT_AUTHORIZED = "NotAuthorizedException";

    private static final Logger log = LoggerFactory.getLogger(CognitoUserInfoClient.class);

    private final RestClient cognitoRestClient;
    private final ObjectMapper mapper;

    public CognitoUserInfoClient(RestClient cognitoRestClient, ObjectMapper mapper) {
        this.cognitoRestClient = cognitoRestClient;
        this.mapper = mapper;
    }

    /** @return sub·email·name. name은 Cognito 속성이 없으면 null */
    public CognitoUser getUser(String accessToken) {
        try {
            String request = mapper.writeValueAsString(Map.of("AccessToken", accessToken));
            return cognitoRestClient.post()
                .uri("/")
                .contentType(AMZ_JSON)
                .header(TARGET_HEADER, GET_USER_TARGET)
                .body(request)
                .exchange((req, res) -> {
                    int status = res.getStatusCode().value();
                    // Content-Type에 charset이 없어(application/x-amz-json-1.1) 문자열로 받으면 ISO-8859-1로 깨진다 → 바이트로 받아 UTF-8 파싱
                    JsonNode body = readJson(res.bodyTo(byte[].class));
                    if (status >= 200 && status < 300) {
                        return parse(body);
                    }
                    String type = body.path("__type").asText("");
                    if (NOT_AUTHORIZED.equals(type)) {
                        log.info("cognito GetUser rejected the token status={} type={}", status, type);
                        throw UserErrorCode.TOKEN_REJECTED.exception(
                            ErrorDetail.of("Authorization", null, UserErrorCode.TOKEN_REJECTED.name()));
                    }
                    log.warn("cognito GetUser failed status={} type={}", status, type);
                    throw UserErrorCode.IDENTITY_UNAVAILABLE.exception();
                });
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            // 타임아웃·연결 오류·본문 읽기 실패
            log.warn("cognito GetUser call failed cause={}: {}", e.getClass().getSimpleName(), e.getMessage());
            throw UserErrorCode.IDENTITY_UNAVAILABLE.exception(e);
        } catch (IOException e) {
            throw UserErrorCode.IDENTITY_UNAVAILABLE.exception(e);
        }
    }

    private JsonNode readJson(byte[] body) {
        if (body == null || body.length == 0) {
            return mapper.nullNode();
        }
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            log.warn("cognito GetUser returned a body that is not JSON");
            return mapper.nullNode();
        }
    }

    /** {@code {"Username": ..., "UserAttributes": [{"Name": "sub", "Value": ...}, ...]}}. 모르는 속성은 무시한다 */
    private static CognitoUser parse(JsonNode body) {
        String sub = null;
        String email = null;
        String name = null;
        for (JsonNode attribute : body.path("UserAttributes")) {
            String attributeName = attribute.path("Name").asText("");
            String value = attribute.path("Value").isTextual() ? attribute.path("Value").asText() : null;
            switch (attributeName) {
                case "sub" -> sub = value;
                case "email" -> email = value;
                case "name" -> name = value;
                default -> {
                    // 다른 표준·사용자 정의 속성은 쓰지 않는다
                }
            }
        }
        if (sub == null || sub.isBlank() || email == null || email.isBlank()) {
            log.warn("cognito GetUser response lacks sub or email");
            throw UserErrorCode.IDENTITY_UNAVAILABLE.exception(
                ErrorDetail.of(email == null || email.isBlank() ? "email" : "sub", null, "MISSING"));
        }
        return new CognitoUser(sub, email, name);
    }

    /** Cognito 표준 속성 중 프로필에 쓰는 셋 */
    public record CognitoUser(String sub, String email, String name) {
    }
}
