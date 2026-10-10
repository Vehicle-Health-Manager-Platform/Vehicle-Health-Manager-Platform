package com.autocare.platform.merchant;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.service.MerchantActor;
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

/** 本店资料：店长与店员均可读，只有店长可改；品类、区域、资质不经本接口。 */
@RestController
public class MerchantProfileController {
    private final ObjectProvider<MerchantProfile> services;

    public MerchantProfileController(ObjectProvider<MerchantProfile> services) {
        this.services = services;
    }

    private MerchantProfile configured() {
        MerchantProfile service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "门店数据库尚未配置");
        return service;
    }

    private static void noQuery(MultiValueMap<String, String> params) {
        if (!params.isEmpty()) throw MerchantStaffInput.bad();
    }

    @GetMapping("/api/merchant/profile")
    public ResponseEntity<?> read(@AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String, String> params) {
        var actor = MerchantActor.from(jwt);
        noQuery(params);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(configured().read(actor)));
    }

    @PutMapping(value = "/api/merchant/profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                    @RequestBody String raw,
                                    @RequestParam MultiValueMap<String, String> params) {
        var actor = MerchantActor.from(jwt);
        noQuery(params);
        WriteIntegrityService.normalizeKey(key);
        // 写作用域已经返回存储的信封，绝不再包第二层；白名单校验在边界先行。
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(configured().update(actor, key, MerchantStaffInput.profile(MerchantStaffInput.parse(raw))));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<?> database() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
            .body(ApiResponse.error(50300, "门店资料服务暂不可用，请使用原幂等键重试"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> failure(ResponseStatusException error) {
        int status = error.getStatusCode().value();
        int code = status == 400 ? 40001 : status * 100;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body(ApiResponse.error(code, error.getReason()));
    }
}
