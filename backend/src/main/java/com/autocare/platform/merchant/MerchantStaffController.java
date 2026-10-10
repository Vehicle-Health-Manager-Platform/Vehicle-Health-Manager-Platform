package com.autocare.platform.merchant;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.service.MerchantActor;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 店长维护本店店员与技师；店员可读列表但不能执行管理动作。 */
@RestController
public class MerchantStaffController {
    private final ObjectProvider<MerchantStaff> services;

    public MerchantStaffController(ObjectProvider<MerchantStaff> services) {
        this.services = services;
    }

    private MerchantStaff configured() {
        MerchantStaff service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "门店员工数据库尚未配置");
        return service;
    }

    private static void fields(MultiValueMap<String, String> params, String... allowed) {
        if (!Set.of(allowed).containsAll(params.keySet())) throw MerchantStaffInput.bad();
    }

    private static int number(MultiValueMap<String, String> params, String name, int fallback, int max) {
        var value = params.get(name);
        if (value == null) return fallback;
        if (value.size() != 1 || value.get(0) == null || !value.get(0).matches("[1-9][0-9]{0,6}")) throw MerchantStaffInput.bad();
        int parsed = Integer.parseInt(value.get(0));
        if (parsed > max) throw MerchantStaffInput.bad();
        return parsed;
    }

    private static ResponseEntity<?> ok(Object data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));
    }

    /** 写作用域已经返回存储的信封，绝不再包第二层。 */
    private static ResponseEntity<?> wrote(Object envelope) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(envelope);
    }

    @GetMapping("/api/merchant/staff")
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String, String> params) {
        var actor = MerchantActor.from(jwt);
        fields(params, "role", "page", "page_size");
        String role = null;
        var roles = params.get("role");
        if (roles != null) {
            if (roles.size() != 1 || roles.get(0) == null) throw MerchantStaffInput.bad();
            role = MerchantStaffInput.role(roles.get(0));
        }
        int page = number(params, "page", 1, 1000000), size = number(params, "page_size", 20, 50);
        return ok(configured().list(actor, role, page, size));
    }

    @PostMapping(value = "/api/merchant/staff", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                    @RequestBody String raw,
                                    @RequestParam MultiValueMap<String, String> params) {
        var actor = MerchantActor.from(jwt);
        fields(params);
        WriteIntegrityService.normalizeKey(key);
        // 严格校验落在 HTTP 边界：服务层被替换或未装配时也必须先拒绝畸形正文。
        return wrote(configured().create(actor, key, MerchantStaffInput.creation(MerchantStaffInput.parse(raw))));
    }

    @PostMapping(value = "/api/merchant/staff/{id}/disable", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> disable(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                     @RequestBody String raw,
                                     @RequestParam MultiValueMap<String, String> params) {
        var actor = MerchantActor.from(jwt);
        fields(params);
        MerchantStaffInput.id(id);
        WriteIntegrityService.normalizeKey(key);
        // 正文只允许为空对象；停用不需要额外参数。
        JsonNode body = MerchantStaffInput.parse(raw);
        if (body.size() != 0) throw MerchantStaffInput.bad();
        return wrote(configured().disable(actor, id, key));
    }

    @PostMapping(value = "/api/merchant/staff/{id}/enable", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> enable(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                    @RequestBody String raw,
                                    @RequestParam MultiValueMap<String, String> params) {
        var actor = MerchantActor.from(jwt);
        fields(params);
        MerchantStaffInput.id(id);
        WriteIntegrityService.normalizeKey(key);
        JsonNode body = MerchantStaffInput.parse(raw);
        if (body.size() != 0) throw MerchantStaffInput.bad();
        return wrote(configured().enable(actor, id, key));
    }

    /** 员工码是轮换语义：不使用幂等键，每次签发都会让旧码失效。 */
    @PostMapping("/api/merchant/staff/{id}/employee-code")
    public ResponseEntity<?> issueCode(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                       @RequestParam MultiValueMap<String, String> params) {
        var actor = MerchantActor.from(jwt);
        fields(params);
        MerchantStaffInput.id(id);
        return ok(configured().issueCode(actor, id));
    }

    @DeleteMapping("/api/merchant/staff/{id}/employee-code")
    public ResponseEntity<?> revokeCode(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                        @RequestParam MultiValueMap<String, String> params) {
        var actor = MerchantActor.from(jwt);
        fields(params);
        MerchantStaffInput.id(id);
        return ok(configured().revokeCode(actor, id));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<?> database() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
            .body(ApiResponse.error(50300, "门店员工服务暂不可用，请使用原幂等键重试"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> failure(ResponseStatusException error) {
        int status = error.getStatusCode().value();
        int code = status == 400 ? 40001 : status * 100;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body(ApiResponse.error(code, error.getReason()));
    }
}
