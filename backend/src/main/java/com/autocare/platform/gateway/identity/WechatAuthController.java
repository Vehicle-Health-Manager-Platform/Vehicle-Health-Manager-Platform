package com.autocare.platform.gateway.identity;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.gateway.wechat.WechatCode2SessionClient;
import com.autocare.platform.gateway.wechat.WechatPhoneClient;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class WechatAuthController {
    private final WechatCode2SessionClient wechat;
    private final WechatPhoneClient phone;
    private final ObjectProvider<IdentityRepository> repositories;
    private final ObjectProvider<AuthRateLimiter> limiters;
    private final AuthTokens tokens;
    private final String appId;

    public WechatAuthController(WechatCode2SessionClient wechat, WechatPhoneClient phone,
                                ObjectProvider<IdentityRepository> repositories,
                                ObjectProvider<AuthRateLimiter> limiters, AuthTokens tokens,
                                @Value("${WECHAT_APP_ID:}") String appId) {
        this.wechat = wechat;
        this.phone = phone;
        this.repositories = repositories;
        this.limiters = limiters;
        this.tokens = tokens;
        this.appId = appId;
    }

    public record LoginRequest(String code, String role) {}
    public record BindRequest(String employee_code) {}
    public record PhoneRequest(String code) {}
    public record RefreshRequest(String refresh_token) {}

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
            return ApiResponse.success(tokens.owner(owner, appId));
        }
        var technician = repository.technicianByOpenid(appId, openid);
        if (technician.isEmpty()) {
            return ApiResponse.success(Map.of("status", "BIND_REQUIRED", "binding_token",
                tokens.binding(openid, appId), "expires_in", 300));
        }
        if (!technician.get().active()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "员工或商家不可用");
        }
        return ApiResponse.success(tokens.technician(technician.get(), appId));
    }

    @PostMapping("/technician/bind")
    public ApiResponse<Map<String, Object>> bind(@AuthenticationPrincipal Jwt jwt, @RequestBody BindRequest request) {
        if (jwt == null || !"wechat_binding".equals(jwt.getClaimAsString("subject_type"))
            || !"BIND".equals(jwt.getClaimAsString("role")) || !appId.equals(jwt.getClaimAsString("app_id"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "绑定凭证无效");
        }
        String code = request == null ? null : request.employee_code();
        AuthRateLimiter limiter = requiredLimiter();
        limiter.check("bind-openid", appId + ":" + jwt.getSubject(), 5, 900);
        if (code != null && !code.isBlank()) limiter.check("bind-code", appId + ":" + code, 10, 900);
        return ApiResponse.success(tokens.technician(requiredRepository().bindTechnician(appId, jwt.getSubject(), code), appId));
    }

    @PostMapping("/phone/bind")
    public ApiResponse<Map<String, Object>> bindPhone(@AuthenticationPrincipal Jwt jwt,
                                                        @RequestBody PhoneRequest request) {
        if (jwt == null || !"user".equals(jwt.getClaimAsString("subject_type"))
            || !"OWNER".equals(jwt.getClaimAsString("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅车主可绑定手机号");
        }
        long ownerId = Long.parseLong(jwt.getSubject());
        IdentityRepository repository = requiredRepository();
        String openid = repository.ownerOpenidById(ownerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "账号不可用"));
        requiredLimiter().check("phone-owner", Long.toString(ownerId), 10, 3600);
        String number = phone.exchange(request == null ? null : request.code(), openid);
        repository.bindOwnerPhone(ownerId, number);
        return ApiResponse.success(Map.of("phone_bound", true, "phone_masked", mask(number)));
    }

    @PostMapping("/refresh")
    public ApiResponse<Map<String, Object>> refresh(@RequestBody RefreshRequest request) {
        return ApiResponse.success(tokens.refresh(request == null ? null : request.refresh_token()));
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Boolean>> logout(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null || jwt.getClaimAsString("jti") == null
            || !("user".equals(jwt.getClaimAsString("subject_type"))
                || "staff_account".equals(jwt.getClaimAsString("subject_type")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无有效业务会话");
        }
        tokens.logout(jwt.getClaimAsString("jti"));
        return ApiResponse.success(Map.of("revoked", true));
    }

    private static String mask(String number) {
        if (number.length() < 7) return "******";
        return number.substring(0, 3) + "****" + number.substring(number.length() - 4);
    }

    private IdentityRepository requiredRepository() {
        IdentityRepository repository = repositories.getIfAvailable();
        if (repository == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "身份数据库尚未配置");
        }
        return repository;
    }

    private AuthRateLimiter requiredLimiter() {
        AuthRateLimiter limiter = limiters.getIfAvailable();
        if (limiter == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "限流数据库尚未配置");
        return limiter;
    }
}
