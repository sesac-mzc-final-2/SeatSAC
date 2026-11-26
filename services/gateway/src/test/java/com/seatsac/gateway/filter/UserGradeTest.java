package com.seatsac.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class UserGradeTest {

    @Test
    void 그룹_클레임에_VIP가_있을_때만_VIP() {
        assertThat(UserGrade.fromToken(jwt(Map.of("cognito:groups", List.of("VIP"))))).isEqualTo("VIP");
        assertThat(UserGrade.fromToken(jwt(Map.of("cognito:groups", List.of("ADMIN", "VIP"))))).isEqualTo("VIP");
        assertThat(UserGrade.fromToken(jwt(Map.of("cognito:groups", List.of("ADMIN"))))).isEqualTo("NORMAL");
        assertThat(UserGrade.fromToken(jwt(Map.of("cognito:groups", List.of())))).isEqualTo("NORMAL");
        assertThat(UserGrade.fromToken(jwt(Map.of()))).isEqualTo("NORMAL");
    }

    @Test
    void 대소문자가_다르거나_배열이_아니면_NORMAL() {
        assertThat(UserGrade.fromToken(jwt(Map.of("cognito:groups", List.of("vip"))))).isEqualTo("NORMAL");
        assertThat(UserGrade.fromToken(jwt(Map.of("cognito:groups", "VIP")))).isEqualTo("NORMAL");
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("sub")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
