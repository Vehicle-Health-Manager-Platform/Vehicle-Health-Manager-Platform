package com.autocare.platform.gateway;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.gateway.identity.IdentityRepository;
import com.autocare.platform.gateway.identity.AuthSessionRepository;
import com.autocare.platform.gateway.identity.AuthTokens;
import com.autocare.platform.gateway.identity.MerchantIdentityRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    private static final String ISSUER = "vehicle-health-manager";

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper, com.autocare.platform.file.UploadAdmissionFilter uploads) throws Exception {
        org.springframework.security.web.AuthenticationEntryPoint unauthorized = (request, response, exception) -> {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Cache-Control", "no-store");
            mapper.writeValue(response.getWriter(), ApiResponse.error(40100, "未登录或登录已失效"));
        };
        org.springframework.security.web.access.AccessDeniedHandler forbidden = (request, response, exception) -> {
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Cache-Control", "no-store");
            mapper.writeValue(response.getWriter(), ApiResponse.error(40300, "无权访问"));
        };
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/prometheus", "/api/dev/token", "/api/auth/wx-login", "/api/auth/cloud-login", "/api/auth/refresh", "/api/auth/merchant/code", "/api/auth/merchant/login", "/api/auth/operator/code", "/api/auth/operator/login").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/payments/callback/LOCAL_TEST").permitAll()
                .requestMatchers("/api/auth/technician/bind").authenticated()
                .anyRequest().access((authentication, context) -> {
                    boolean allowed = authentication.get().getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt jwt
                        && !"wechat_binding".equals(jwt.getClaimAsString("subject_type"));
                    return new org.springframework.security.authorization.AuthorizationDecision(allowed);
                }))
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
                .authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
            .addFilterAfter(uploads, org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
            .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
            .build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${JWT_SECRET:}") String secret,
                          @Value("${WECHAT_APP_ID:}") String appId,
                          ObjectProvider<IdentityRepository> repositories,
                          ObjectProvider<MerchantIdentityRepository> merchants,
                          ObjectProvider<AuthSessionRepository> sessions,
                          ObjectProvider<com.autocare.platform.gateway.identity.OperatorIdentity> operators) {
        byte[] key = checkedKey(secret);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256).build();
        var standard = JwtValidators.createDefaultWithIssuer(ISSUER);
        decoder.setJwtValidator(jwt -> {
            var result = standard.validate(jwt);
            if (result.hasErrors()) return result;
            String type = jwt.getClaimAsString("subject_type");
            if (type == null || "wechat_binding".equals(type)) return result;
            if ("operator_account".equals(type)) {
                var operator=operators.getIfAvailable();
                return operator!=null && operator.valid(jwt)?result:invalidIdentity();
            }
            IdentityRepository repository = repositories.getIfAvailable();
            AuthSessionRepository sessionRepository = sessions.getIfAvailable();
            if (repository == null || sessionRepository == null) return invalidIdentity();
            try {
                String sessionId = jwt.getClaimAsString("jti");
                if (sessionId == null || !sessionRepository.active(sessionId)) return invalidIdentity();
                long subjectId = Long.parseLong(jwt.getSubject());
                boolean valid = switch (type) {
                    case "user" -> "OWNER".equals(jwt.getClaimAsString("role"))
                        && repository.ownerById(subjectId).filter(owner -> owner.status() == 1 && !owner.deleted()).isPresent();
                    case "staff_account" -> {
                        Number bindingId = jwt.getClaim("binding_id");
                        Number merchantId = jwt.getClaim("merchant_id");
                        boolean technician = "TECHNICIAN".equals(jwt.getClaimAsString("role")) && bindingId != null && merchantId != null
                            && appId.equals(jwt.getClaimAsString("app_id"))
                            && repository.technicianByBindingId(bindingId.longValue())
                                .filter(staff -> staff.active() && staff.staffId() == subjectId
                                    && staff.merchantId() == merchantId.longValue()).isPresent();
                        MerchantIdentityRepository merchantRepository = merchants.getIfAvailable();
                        boolean merchant = "MERCHANT".equals(jwt.getClaimAsString("role")) && bindingId == null
                            && merchantId != null && AuthTokens.MERCHANT_APP_ID.equals(jwt.getClaimAsString("app_id"))
                            && merchantRepository != null && merchantRepository.byId(subjectId)
                                .filter(staff -> staff.active() && staff.merchantId() == merchantId.longValue()).isPresent();
                        yield technician || merchant;
                    }
                    default -> false;
                };
                return valid ? result : invalidIdentity();
            } catch (RuntimeException exception) {
                return invalidIdentity();
            }
        });
        return decoder;
    }

    private static OAuth2TokenValidatorResult invalidIdentity() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Identity inactive", null));
    }

    @Bean
    JwtEncoder jwtEncoder(@Value("${JWT_SECRET:}") String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(checkedKey(secret)));
    }

    private byte[] checkedKey(String secret) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 UTF-8 bytes");
        }
        return key;
    }

    public static String issuer() {
        return ISSUER;
    }
}
