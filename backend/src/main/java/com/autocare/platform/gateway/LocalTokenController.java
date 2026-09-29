package com.autocare.platform.gateway;

import com.autocare.platform.common.ApiResponse;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@RequestMapping("/api/dev")
public class LocalTokenController {
    private final JwtEncoder encoder;

    public LocalTokenController(JwtEncoder encoder) {
        this.encoder = encoder;
    }

    @PostMapping("/token")
    public ApiResponse<Map<String, String>> issue(@RequestBody Map<String, String> request) {
        String userId = request.get("user_id");
        if (!"1001".equals(userId) && !"2001".equals(userId)) {
            throw new IllegalArgumentException("Only local fixture users 1001 and 2001 are allowed");
        }
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(SecurityConfig.issuer())
            .subject(userId)
            .claim("role", "OWNER")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(900))
            .build();
        String token = encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return ApiResponse.success(Map.of("access_token", token, "token_type", "Bearer"));
    }
}
