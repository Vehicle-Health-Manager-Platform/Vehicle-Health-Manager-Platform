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
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ArchiveStructuredController {
    private final ObjectProvider<ArchiveStructuredService> services;
    public ArchiveStructuredController(ObjectProvider<ArchiveStructuredService> services) { this.services = services; }
    private ArchiveStructuredService service() {
        var service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "资料服务尚未配置");
        return service;
    }
    @GetMapping("/api/archive/{id}/structured")
    public ResponseEntity<?> context(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestParam MultiValueMap<String,String> query) {
        var owner = VehicleOwner.from(jwt); OwnerArchiveQuery.validate(query); return ok(ApiResponse.success(service().context(owner, id)));
    }
    @PutMapping("/api/archive/{id}/structured")
    public ResponseEntity<?> save(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                @RequestHeader(value="Idempotency-Key", required=false) String key, @RequestBody JsonNode body, @RequestParam MultiValueMap<String,String> query) {
        var owner = VehicleOwner.from(jwt); OwnerArchiveQuery.validate(query); WriteIntegrityService.normalizeKey(key);
        var input = ArchiveStructuredInput.parse(body); return ok(service().save(owner, id, key, input));
    }
    private ResponseEntity<?> ok(Object body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> failure(ResponseStatusException e) {
        int status = e.getStatusCode().value(); return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(status == 400 ? 40001 : status * 100, e.getReason()));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> failure() { return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300, "资料服务暂不可用，请稍后重试")); }
}
