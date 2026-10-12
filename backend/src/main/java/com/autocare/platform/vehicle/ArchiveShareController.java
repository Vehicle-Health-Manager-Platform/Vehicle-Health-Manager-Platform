package com.autocare.platform.vehicle;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ArchiveShareController {
    private final ObjectProvider<ArchiveShareService> services;
    public ArchiveShareController(ObjectProvider<ArchiveShareService> services) { this.services = services; }
    private ArchiveShareService service() {
        var service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "分享服务尚未配置");
        return service;
    }
    @PostMapping("/api/archive/shares")
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value="Idempotency-Key", required=false) String key, @RequestBody JsonNode body) {
        var owner = VehicleOwner.from(jwt); WriteIntegrityService.normalizeKey(key);
        var input = ArchiveShareInput.parse(body);
        return ok(service().create(owner, key, input));
    }
    @GetMapping("/api/archive/shares")
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt, @RequestParam("vehicle_id") long vehicle) {
        var owner = VehicleOwner.from(jwt);
        return ok(ApiResponse.success(service().list(owner, vehicle)));
    }
    @GetMapping("/api/archive/shares/{id}/link")
    public ResponseEntity<?> link(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        var owner = VehicleOwner.from(jwt);
        return ok(ApiResponse.success(service().link(owner, id)));
    }
    @PostMapping("/api/archive/shares/{id}/revoke")
    public ResponseEntity<?> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                   @RequestHeader(value="Idempotency-Key", required=false) String key, @RequestBody JsonNode body) {
        var owner = VehicleOwner.from(jwt); WriteIntegrityService.normalizeKey(key);
        return ok(service().revoke(owner, id, key, body));
    }
    @PostMapping("/api/archive/share/view")
    public ResponseEntity<?> view(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode body) {
        var owner = VehicleOwner.from(jwt);
        if (body == null || !body.isObject() || body.size() != 1 || !body.path("share_token").isTextual()) throw WriteIntegrityService.badRequest("分享请求参数无效");
        return ok(ApiResponse.success(service().view(owner, body.path("share_token").textValue())));
    }
    private static ResponseEntity<?> ok(Object body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> failure(ResponseStatusException e) {
        int status = e.getStatusCode().value();
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(status == 400 ? 40001 : status * 100, e.getReason()));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300, "分享服务暂不可用，请稍后重试"));
    }
}
