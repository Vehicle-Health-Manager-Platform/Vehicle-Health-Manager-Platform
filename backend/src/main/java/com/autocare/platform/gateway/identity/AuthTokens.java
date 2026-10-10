package com.autocare.platform.gateway.identity;

import com.autocare.platform.gateway.SecurityConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthTokens {
    private static final long ACCESS_SECONDS = 900;
    private static final long REFRESH_SECONDS = 30L * 24 * 3600;
    public static final String MERCHANT_APP_ID = "merchant-account";
    private final JwtEncoder encoder;
    private final ObjectProvider<AuthSessionRepository> sessions;
    private final ObjectProvider<IdentityRepository> identities;
    private final ObjectProvider<MerchantIdentityRepository> merchants;
    private final String currentAppId;
    private final SecureRandom random = new SecureRandom();

    public AuthTokens(JwtEncoder encoder, ObjectProvider<AuthSessionRepository> sessions,
                      ObjectProvider<IdentityRepository> identities,
                      ObjectProvider<MerchantIdentityRepository> merchants,
                      @Value("${WECHAT_APP_ID:}") String currentAppId) {
        this.encoder = encoder;
        this.sessions = sessions;
        this.identities = identities;
        this.merchants = merchants;
        this.currentAppId = currentAppId;
    }

    public Map<String, Object> owner(IdentityRepository.Owner owner, String appId) {
        return issue(new AuthSessionRepository.Session(UUID.randomUUID().toString(), "user", owner.id(),
            "OWNER", appId, null, null), ownerUser(owner));
    }

    public Map<String, Object> technician(IdentityRepository.Technician technician, String appId) {
        return issue(new AuthSessionRepository.Session(UUID.randomUUID().toString(), "staff_account",
            technician.staffId(), "TECHNICIAN", appId, technician.id(), technician.merchantId()),
            technicianUser(technician));
    }

    public Map<String, Object> merchant(MerchantIdentityRepository.Merchant merchant) {
        // 店长/店员共用同一登录入口，令牌角色必须等于员工行的真实角色。
        return issue(new AuthSessionRepository.Session(UUID.randomUUID().toString(), "staff_account",
            merchant.staffId(), merchant.role(), MERCHANT_APP_ID, null, merchant.merchantId()),
            merchantUser(merchant));
    }

    public String binding(String openid, String appId) {
        return jwt(openid, "BIND", "wechat_binding", null, 300, Map.of("app_id", appId));
    }

    @Transactional
    public Map<String, Object> refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.length() < 40 || refreshToken.length() > 200) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "刷新凭证无效");
        }
        String next = randomToken();
        var session = requiredSessions().rotate(sha256(refreshToken), sha256(next))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "刷新凭证无效或已失效"));
        if (!currentAppId.equals(session.appId())
            && !(isStorefrontRole(session.role()) && MERCHANT_APP_ID.equals(session.appId()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "刷新凭证无效");
        }
        Map<String, Object> user = currentUser(session);
        return response(session, next, user);
    }

    public void logout(String sessionId) {
        requiredSessions().revoke(sessionId);
    }

    private Map<String, Object> issue(AuthSessionRepository.Session session, Map<String, Object> user) {
        String refreshToken = randomToken();
        requiredSessions().create(session, sha256(refreshToken), Instant.now().plusSeconds(REFRESH_SECONDS));
        return response(session, refreshToken, user);
    }

    private Map<String, Object> response(AuthSessionRepository.Session session, String refreshToken,
                                         Map<String, Object> user) {
        Map<String, Object> extra = session.bindingId() != null
            ? Map.of("app_id", session.appId(), "binding_id", session.bindingId(), "merchant_id", session.merchantId())
            : session.merchantId() != null
                ? Map.of("app_id", session.appId(), "merchant_id", session.merchantId())
                : Map.of("app_id", session.appId());
        return Map.of("access_token", jwt(Long.toString(session.subjectId()), session.role(), session.subjectType(),
                session.id(), ACCESS_SECONDS, extra), "refresh_token", refreshToken,
            "token_type", "Bearer", "expires_in", ACCESS_SECONDS, "user", user);
    }

    private Map<String, Object> currentUser(AuthSessionRepository.Session session) {
        IdentityRepository repository = identities.getIfAvailable();
        if (repository == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "身份数据库尚未配置");
        if ("user".equals(session.subjectType()) && "OWNER".equals(session.role())) {
            return repository.ownerById(session.subjectId())
                .filter(owner -> owner.status() == 1 && !owner.deleted()).map(AuthTokens::ownerUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "账号不可用"));
        }
        if ("staff_account".equals(session.subjectType()) && "TECHNICIAN".equals(session.role())
            && session.bindingId() != null && session.merchantId() != null) {
            return repository.technicianByBindingId(session.bindingId())
                .filter(tech -> tech.active() && tech.staffId() == session.subjectId()
                    && tech.merchantId() == session.merchantId())
                .map(AuthTokens::technicianUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "员工或商家不可用"));
        }
        if ("staff_account".equals(session.subjectType()) && isStorefrontRole(session.role())
            && MERCHANT_APP_ID.equals(session.appId()) && session.merchantId() != null) {
            MerchantIdentityRepository merchantRepository = merchants.getIfAvailable();
            if (merchantRepository == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "身份数据库尚未配置");
            return merchantRepository.byId(session.subjectId())
                .filter(merchant -> merchant.active() && session.role().equals(merchant.role())
                    && merchant.merchantId() == session.merchantId())
                .map(AuthTokens::merchantUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "员工或商家不可用"));
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "刷新凭证无效");
    }

    private static boolean isStorefrontRole(String role) {
        return "MERCHANT".equals(role) || "STAFF".equals(role);
    }

    private String jwt(String subject, String role, String type, String sessionId, long seconds,
                       Map<String, Object> extra) {
        Instant now = Instant.now();
        var builder = JwtClaimsSet.builder().issuer(SecurityConfig.issuer()).subject(subject)
            .issuedAt(now).expiresAt(now.plusSeconds(seconds)).claim("role", role).claim("subject_type", type);
        if (sessionId != null) builder.id(sessionId);
        extra.forEach(builder::claim);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), builder.build()))
            .getTokenValue();
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private AuthSessionRepository requiredSessions() {
        AuthSessionRepository repository = sessions.getIfAvailable();
        if (repository == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "会话数据库尚未配置");
        return repository;
    }

    private static Map<String, Object> ownerUser(IdentityRepository.Owner owner) {
        return Map.of("id", owner.id(), "role", "owner", "phone_bound",
            owner.phone() != null && !owner.phone().isBlank());
    }

    private static Map<String, Object> technicianUser(IdentityRepository.Technician tech) {
        return Map.of("id", tech.staffId(), "role", "technician", "merchant_id", tech.merchantId());
    }

    private static Map<String, Object> merchantUser(MerchantIdentityRepository.Merchant merchant) {
        return Map.of("id", merchant.staffId(), "role", merchant.manager() ? "merchant" : "staff",
            "merchant_id", merchant.merchantId());
    }
}
