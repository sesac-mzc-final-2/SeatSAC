package com.seatsac.gateway.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Cognito Access Token 전용 검사: ID Token(token_use=id)과 다른 앱 클라이언트의 토큰을 거절한다. */
class CognitoAccessTokenValidator implements OAuth2TokenValidator<Jwt> {

    private final String clientId;

    CognitoAccessTokenValidator(String clientId) {
        this.clientId = clientId;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (!"access".equals(jwt.getClaimAsString("token_use"))) {
            return failure("token_use must be access");
        }
        if (!clientId.equals(jwt.getClaimAsString("client_id"))) {
            return failure("client_id mismatch");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
