package com.autocare.platform.vehicle;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ArchiveReminderController {
    private final ObjectProvider<ArchiveReminderService> services;
    public ArchiveReminderController(ObjectProvider<ArchiveReminderService> services) { this.services = services; }
    private ArchiveReminderService service() {
        var service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "提醒服务尚未配置");
        return service;
    }
    @GetMapping("/api/archive/{id}/reminder")
    public ResponseEntity<?> context(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        var owner = VehicleOwner.from(jwt); return ok(ApiResponse.success(service().context(owner, id)));
    }
    @GetMapping("/api/vehicle/reminders")
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt, @RequestParam("vehicle_id") long vehicle) {
        var owner = VehicleOwner.from(jwt); return ok(ApiResponse.success(service().list(owner, vehicle)));
    }
    @PutMapping("/api/archive/{id}/reminder")
    public ResponseEntity<?> save(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                @RequestHeader(value="Idempotency-Key", required=false) String key, @RequestBody JsonNode body) {
        var owner = VehicleOwner.from(jwt); WriteIntegrityService.normalizeKey(key);
        var input = ArchiveReminderInput.parse(body); return ok(service().save(owner, id, key, input));
    }
    private ResponseEntity<?> ok(Object body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> failure(ResponseStatusException e) {
        int status = e.getStatusCode().value(); return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(status == 400 ? 40001 : status * 100, e.getReason()));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> failure() { return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300, "提醒服务暂不可用，请稍后重试")); }
}
