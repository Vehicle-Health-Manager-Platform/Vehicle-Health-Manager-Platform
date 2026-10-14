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
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.autocare.platform.order.InvitationRegistration invitations;
    @Value("${INVITE_VERIFIED_IDENTITY_ENABLED:false}")
    private boolean inviteVerified;

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

    public record BindRequest(String employee_code) {}
    public record RefreshRequest(String refresh_token) {}

    @PostMapping("/wx-login")
    public ApiResponse<Map<String, Object>> login(@RequestBody String raw) {
        var body=com.autocare.platform.order.InvitationInput.login(raw,false);
        String role=body.path("role").textValue();
        String invite=com.autocare.platform.order.InvitationInput.code(body.get("invite_code"));
        IdentityRepository repository = requiredRepository();
        String openid = wechat.exchange(body.path("code").textValue()).openid();
        if ("owner".equals(role)) {
            if(invite!=null&&invitations==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"邀请数据库尚未配置");
            var registration=invitations==null?null:invitations.registerForLogin(openid,invite,inviteVerified&&wechat.officialEndpoint()?"VERIFIED_WECHAT":"UNKNOWN",appId);
            var owner = registration==null?repository.createOwnerOrRead(openid):repository.ownerById(registration.ownerId()).orElseThrow();
            if (owner.status() != 1 || owner.deleted()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号不可用");
            }
            var data=new java.util.LinkedHashMap<String,Object>(tokens.owner(owner,appId));
            if(invite!=null)data.put("invitation_result",registration.invitationResult());
            return ApiResponse.success(data);
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
                                                        @RequestBody String raw) {
        String phoneCode=com.autocare.platform.order.InvitationInput.phone(raw);
        if (jwt == null || !"user".equals(jwt.getClaimAsString("subject_type"))
            || !"OWNER".equals(jwt.getClaimAsString("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅车主可绑定手机号");
        }
        long ownerId = Long.parseLong(jwt.getSubject());
        IdentityRepository repository = requiredRepository();
        String openid = repository.ownerOpenidById(ownerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "账号不可用"));
        requiredLimiter().check("phone-owner", Long.toString(ownerId), 10, 3600);
        String number = phone.exchange(phoneCode, openid);
        if(invitations==null)repository.bindOwnerPhone(ownerId, number);
        else invitations.bindPhone(com.autocare.platform.vehicle.VehicleOwner.from(jwt),number,appId,inviteVerified&&phone.officialEndpoints());
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
