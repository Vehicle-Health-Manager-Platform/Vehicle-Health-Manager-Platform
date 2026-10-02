package com.autocare.platform.gateway.identity;

import com.autocare.platform.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth/merchant")
public class MerchantAuthController {
    private final ObjectProvider<MerchantIdentityRepository> repositories;
    private final ObjectProvider<MerchantSmsSender> senders;
    private final ObjectProvider<AuthRateLimiter> limiters;
    private final AuthTokens tokens;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final String dummyPasswordHash = encoder.encode("not-a-real-merchant-password");
    private final SecureRandom random = new SecureRandom();

    public MerchantAuthController(ObjectProvider<MerchantIdentityRepository> repositories,
                                  ObjectProvider<MerchantSmsSender> senders,
                                  ObjectProvider<AuthRateLimiter> limiters, AuthTokens tokens) {
        this.repositories = repositories;
        this.senders = senders;
        this.limiters = limiters;
        this.tokens = tokens;
    }

    public record CodeRequest(String account, String password) {}
    public record LoginRequest(String account, String password, String sms_code) {}

    @PostMapping("/code")
    public ApiResponse<Map<String, Object>> code(@RequestBody CodeRequest request,
                                                  HttpServletRequest servletRequest) {
        String account = request == null ? null : request.account();
        rateLimit(account, servletRequest);
        var merchant = verified(account, request == null ? null : request.password());
        MerchantSmsSender sender = senders.getIfAvailable();
        if (sender == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "短信服务尚未配置");
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        repository().issueSmsCode(merchant.staffId(), merchant.phone(), encoder.encode(code),
            () -> sender.sendLoginCode(merchant.phone(), code));
        return ApiResponse.success(Map.of("sent", true, "expires_in", 300));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest request,
                                                   HttpServletRequest servletRequest) {
        String account = request == null ? null : request.account();
        rateLimit(account, servletRequest);
        var merchant = verified(account, request == null ? null : request.password());
        String code = request.sms_code();
        if (code == null || !code.matches("[0-9]{6}")
            || !repository().consumeSmsCode(merchant.staffId(), merchant.phone(), code)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号、密码或验证码无效");
        }
        return ApiResponse.success(tokens.merchant(merchant));
    }

    private MerchantIdentityRepository.Merchant verified(String account, String password) {
        if (account == null || account.isBlank() || account.length() > 64 || password == null
            || password.isBlank() || password.length() > 256) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号、密码或验证码无效");
        }
        var merchant = repository().byAccount(account).orElse(null);
        String hash = merchant != null && merchant.passwordHash() != null
            ? merchant.passwordHash() : dummyPasswordHash;
        boolean passwordMatches = encoder.matches(password, hash);
        if (merchant == null || !merchant.active() || merchant.phone() == null
            || !merchant.phone().matches("1[3-9][0-9]{9}") || !passwordMatches) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号、密码或验证码无效");
        }
        return merchant;
    }

    private void rateLimit(String account, HttpServletRequest request) {
        AuthRateLimiter limiter = limiters.getIfAvailable();
        if (limiter == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "限流数据库尚未配置");
        limiter.check("merchant-ip", request.getRemoteAddr(), 30, 900);
        limiter.check("merchant-account", account == null ? "" : account, 10, 900);
    }

    private MerchantIdentityRepository repository() {
        MerchantIdentityRepository repository = repositories.getIfAvailable();
        if (repository == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "身份数据库尚未配置");
        return repository;
    }
}
