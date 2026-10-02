package com.autocare.platform.gateway.identity;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.gateway.SecurityConfig;
import com.autocare.platform.gateway.wechat.WechatCode2SessionClient;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class WechatAuthController {
    private final WechatCode2SessionClient wechat;
    private final ObjectProvider<IdentityRepository> repositories;
    private final JwtEncoder encoder;
    private final String appId;

    public WechatAuthController(WechatCode2SessionClient wechat, ObjectProvider<IdentityRepository> repositories,
                                JwtEncoder encoder, @Value("${WECHAT_APP_ID:}") String appId) {
        this.wechat = wechat;
        this.repositories = repositories;
        this.encoder = encoder;
        this.appId = appId;
    }

    public record LoginRequest(String code, String role) {}
    public record BindRequest(String employee_code) {}

    @PostMapping("/wx-login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest request) {
        if (request == null || !("owner".equals(request.role()) || "technician".equals(request.role()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的登录角色");
        }
        IdentityRepository repository = requiredRepository();
        String openid = wechat.exchange(request.code()).openid();
        if ("owner".equals(request.role())) {
            var owner = repository.createOwnerOrRead(openid);
            if (owner.status() != 1 || owner.deleted()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号不可用");
            }
            return ApiResponse.success(Map.of("access_token", token(Long.toString(owner.id()), "OWNER", "user",
                    900, Map.of()), "token_type", "Bearer", "expires_in", 900,
                "user", Map.of("id", owner.id(), "role", "owner", "phone_bound", owner.phone() != null && !owner.phone().isBlank())));
        }
        var technician = repository.technicianByOpenid(appId, openid);
        if (technician.isEmpty()) {
            return ApiResponse.success(Map.of("status", "BIND_REQUIRED", "binding_token",
                token(openid, "BIND", "wechat_binding", 300, Map.of("app_id", appId)), "expires_in", 300));
        }
        if (!technician.get().active()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "员工或商家不可用");
        }
        return technicianResponse(technician.get());
    }

    @PostMapping("/technician/bind")
    public ApiResponse<Map<String, Object>> bind(@AuthenticationPrincipal Jwt jwt, @RequestBody BindRequest request) {
        if (jwt == null || !"wechat_binding".equals(jwt.getClaimAsString("subject_type"))
            || !"BIND".equals(jwt.getClaimAsString("role")) || !appId.equals(jwt.getClaimAsString("app_id"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "绑定凭证无效");
        }
        return technicianResponse(requiredRepository().bindTechnician(appId, jwt.getSubject(),
            request == null ? null : request.employee_code()));
    }

    private ApiResponse<Map<String, Object>> technicianResponse(IdentityRepository.Technician technician) {
        return ApiResponse.success(Map.of("access_token", token(Long.toString(technician.staffId()), "TECHNICIAN",
                "staff_account", 900, Map.of("app_id", appId, "binding_id", technician.id(),
                    "merchant_id", technician.merchantId())), "token_type", "Bearer", "expires_in", 900,
            "user", Map.of("id", technician.staffId(), "role", "technician", "merchant_id", technician.merchantId())));
    }

    private String token(String subject, String role, String subjectType, long seconds, Map<String, Object> extra) {
        Instant now = Instant.now();
        var builder = JwtClaimsSet.builder().issuer(SecurityConfig.issuer()).subject(subject)
            .issuedAt(now).expiresAt(now.plusSeconds(seconds)).claim("role", role).claim("subject_type", subjectType);
        extra.forEach(builder::claim);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), builder.build()))
            .getTokenValue();
    }

    private IdentityRepository requiredRepository() {
        IdentityRepository repository = repositories.getIfAvailable();
        if (repository == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "身份数据库尚未配置");
        }
        return repository;
    }
}
