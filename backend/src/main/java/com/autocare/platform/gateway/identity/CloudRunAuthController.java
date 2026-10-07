package com.autocare.platform.gateway.identity;

import com.autocare.platform.common.ApiResponse;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 微信云托管身份入口。
 *
 * <p>小程序经 {@code wx.cloud.callContainer} 调用时，微信网关会注入 {@code X-WX-OPENID}
 * 等请求头，容器可直接据此识别用户，无需再用 {@code code2session} 换码。
 *
 * <p>这些请求头由微信链路保证，但**只有在该云托管服务关闭公网访问时才是可信的**：公网可直连时
 * 任何调用方都能伪造。开关关闭时本控制器不注册，端点不存在。
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "WECHAT_CLOUD_RUN_ENABLED", havingValue = "true")
public class CloudRunAuthController {
    static final String SOURCE_HEADER = "X-WX-SOURCE";
    static final String APP_ID_HEADER = "X-WX-APPID";
    static final String OPEN_ID_HEADER = "X-WX-OPENID";

    private final ObjectProvider<IdentityRepository> repositories;
    private final AuthTokens tokens;
    private final String appId;

    public CloudRunAuthController(ObjectProvider<IdentityRepository> repositories, AuthTokens tokens,
                                  @Value("${WECHAT_APP_ID:}") String appId) {
        this.repositories = repositories;
        this.tokens = tokens;
        this.appId = appId;
    }

    public record RoleRequest(String role) {}

    @PostMapping("/cloud-login")
    public ApiResponse<Map<String, Object>> login(
            @RequestHeader(name = SOURCE_HEADER, required = false) String source,
            @RequestHeader(name = APP_ID_HEADER, required = false) String callerAppId,
            @RequestHeader(name = OPEN_ID_HEADER, required = false) String openid,
            @RequestBody(required = false) RoleRequest request) {
        // 仅微信网关转发会带上 X-WX-SOURCE；能否伪造取决于服务是否可被公网直连。
        if (blank(source)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "调用来源无效");
        }
        if (blank(appId) || !appId.equalsIgnoreCase(callerAppId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "小程序应用不匹配");
        }
        if (blank(openid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "微信身份缺失");
        }
        String role = request == null ? null : request.role();
        if (!("owner".equals(role) || "technician".equals(role))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的登录角色");
        }
        IdentityRepository repository = requiredRepository();
        if ("owner".equals(role)) {
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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private IdentityRepository requiredRepository() {
        IdentityRepository repository = repositories.getIfAvailable();
        if (repository == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "身份数据库尚未配置");
        }
        return repository;
    }
}
