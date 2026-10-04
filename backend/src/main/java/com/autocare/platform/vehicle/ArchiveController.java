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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ArchiveController {
    private final ObjectProvider<ArchiveService> services;
    public ArchiveController(ObjectProvider<ArchiveService> services) { this.services = services; }
    private ArchiveService service() {
        ArchiveService service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "档案数据库尚未配置");
        return service;
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> databaseUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
            .body(ApiResponse.error(50300, "档案数据库暂不可用，请稍后重试"));
    }
    @GetMapping("/api/archive/list")
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt, @RequestParam("vehicle_id") long vehicleId,
        @RequestParam(defaultValue="1") int page, @RequestParam(name="page_size",defaultValue="20") int size) {
        VehicleOwner owner = VehicleOwner.from(jwt);
        VehicleService.page(page, size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponse.success(service().list(owner, vehicleId, page, size)));
    }
    @PostMapping("/api/archive/add")
    public ResponseEntity<JsonNode> add(@AuthenticationPrincipal Jwt jwt,
        @RequestHeader(value="Idempotency-Key",required=false) String key, @RequestBody JsonNode body) {
        VehicleOwner owner = VehicleOwner.from(jwt);
        WriteIntegrityService.normalizeKey(key);
        ArchiveInput input = ArchiveInput.parse(body);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service().add(owner, key, input));
    }
}
