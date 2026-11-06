package com.seatsac.user.client;

import com.seatsac.common.test.StubHttpServer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.common.core.error.BusinessException;
import com.seatsac.user.common.UserErrorCode;
import com.seatsac.user.config.ClientConfig;
import com.seatsac.user.config.UserProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cognito GetUser 호출의 결말 — 정상(속성 추출, name 없음 → null), 토큰 거절(401), 그 밖의 실패(503).
 * 요청이 JSON 1.1 프로토콜(X-Amz-Target · application/x-amz-json-1.1 · {"AccessToken"})인지도 본다.
 */
class CognitoUserInfoClientTest {

    private static final String SUB = "11111111-1111-4111-8111-111111111111";

    private StubHttpServer cognito;
    private volatile String responseBody;
    private CognitoUserInfoClient client;

    @BeforeEach
    void start() throws Exception {
        responseBody = "{\"Username\":\"u\",\"UserAttributes\":[{\"Name\":\"sub\",\"Value\":\"" + SUB + "\"},"
            + "{\"Name\":\"email_verified\",\"Value\":\"true\"},{\"Name\":\"name\",\"Value\":\"홍길동\"},"
            + "{\"Name\":\"email\",\"Value\":\"hong@example.com\"}]}";
        cognito = new StubHttpServer("/", request -> responseBody);
        cognito.contentType = "application/x-amz-json-1.1";
        var properties = new UserProperties(new UserProperties.Services("http://127.0.0.1:1", "http://127.0.0.1:1"),
            new UserProperties.Cognito(cognito.baseUrl()));
        client = new CognitoUserInfoClient(new ClientConfig().cognitoRestClient(properties), new ObjectMapper());
    }

    @AfterEach
    void stop() {
        cognito.close();
    }

    @Test
    void sendsJson11GetUserAndReadsStandardAttributes() {
        CognitoUserInfoClient.CognitoUser user = client.getUser("access-token");

        assertThat(user).isEqualTo(new CognitoUserInfoClient.CognitoUser(SUB, "hong@example.com", "홍길동"));
        StubHttpServer.Received request = cognito.received.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.header("X-amz-target")).isEqualTo("AWSCognitoIdentityProviderService.GetUser");
        assertThat(request.header("Content-type")).startsWith("application/x-amz-json-1.1");
        assertThat(request.body()).isEqualTo("{\"AccessToken\":\"access-token\"}");
    }

    @Test
    void missingNameAttributeIsNull() {
        responseBody = "{\"UserAttributes\":[{\"Name\":\"sub\",\"Value\":\"" + SUB + "\"},"
            + "{\"Name\":\"email\",\"Value\":\"hong@example.com\"}]}";

        assertThat(client.getUser("t").name()).isNull();
    }

    @Test
    void responseWithoutEmailIsIdentityUnavailable() {
        responseBody = "{\"UserAttributes\":[{\"Name\":\"sub\",\"Value\":\"" + SUB + "\"}]}";

        assertThatThrownBy(() -> client.getUser("t"))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(UserErrorCode.IDENTITY_UNAVAILABLE);
                assertThat(e.details().get(0).field()).isEqualTo("email");
            });
    }

    @Test
    void notAuthorizedIsRejectedToken401() {
        cognito.status = 400;
        responseBody = "{\"__type\":\"NotAuthorizedException\",\"message\":\"Invalid Access Token\"}";

        assertThatThrownBy(() -> client.getUser("expired"))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(UserErrorCode.TOKEN_REJECTED);
                assertThat(e.code().status()).isEqualTo(401);
            });
    }

    @Test
    void otherClientErrorsAndServerErrorsAreIdentityUnavailable() {
        cognito.status = 400;
        responseBody = "{\"__type\":\"TooManyRequestsException\"}";
        assertThatThrownBy(() -> client.getUser("t"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(UserErrorCode.IDENTITY_UNAVAILABLE));

        cognito.status = 500;
        responseBody = "";
        assertThatThrownBy(() -> client.getUser("t"))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(UserErrorCode.IDENTITY_UNAVAILABLE);
                assertThat(e.code().status()).isEqualTo(503);
                assertThat(e.code().responseCode()).isEqualTo("IDENTITY_UNAVAILABLE");
            });
    }

    @Test
    void connectionFailureIsIdentityUnavailable() {
        var properties = new UserProperties(new UserProperties.Services("http://127.0.0.1:1", "http://127.0.0.1:1"),
            new UserProperties.Cognito("http://127.0.0.1:1"));
        var unreachable = new CognitoUserInfoClient(new ClientConfig().cognitoRestClient(properties), new ObjectMapper());

        assertThatThrownBy(() -> unreachable.getUser("t"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(UserErrorCode.IDENTITY_UNAVAILABLE));
    }
}
