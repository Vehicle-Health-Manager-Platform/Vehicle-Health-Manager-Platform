package com.autocare.platform.vehicle;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class VehicleController {
    private final ObjectProvider<VehicleService> services;
    public VehicleController(ObjectProvider<VehicleService> services) { this.services=services; }
    private VehicleService service() {
        var service=services.getIfAvailable();
        if (service==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"车辆数据库尚未配置");
        return service;
    }
    private ResponseEntity<?> response(Object data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));
    }
    @GetMapping("/api/vehicle/list")
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue="1") int page,
        @RequestParam(name="page_size",defaultValue="20") int size) {
        var owner=VehicleOwner.from(jwt); VehicleService.page(page,size);
        return response(service().list(owner,page,size));
    }
    @GetMapping("/api/brand/list")
    public ResponseEntity<?> brands(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue="1") int page,
        @RequestParam(name="page_size",defaultValue="20") int size) {
        var owner=VehicleOwner.from(jwt); VehicleService.page(page,size);
        return response(service().catalog(owner,"brand",0,page,size));
    }
    @GetMapping("/api/series/list")
    public ResponseEntity<?> series(@AuthenticationPrincipal Jwt jwt, @RequestParam("brand_id") long id,
        @RequestParam(defaultValue="1") int page,@RequestParam(name="page_size",defaultValue="20") int size) {
        var owner=VehicleOwner.from(jwt); VehicleService.page(page,size);
        return response(service().catalog(owner,"series",id,page,size));
    }
    @GetMapping("/api/model/list")
    public ResponseEntity<?> models(@AuthenticationPrincipal Jwt jwt, @RequestParam("series_id") long id,
        @RequestParam(defaultValue="1") int page,@RequestParam(name="page_size",defaultValue="20") int size) {
        var owner=VehicleOwner.from(jwt); VehicleService.page(page,size);
        return response(service().catalog(owner,"model",id,page,size));
    }
    @PostMapping("/api/vehicle/add")
    public ResponseEntity<JsonNode> add(@AuthenticationPrincipal Jwt jwt,
        @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody JsonNode body) {
        var owner=VehicleOwner.from(jwt); WriteIntegrityService.normalizeKey(key);
        var input=VehicleInput.parse(body);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service().add(owner,key,input));
    }
}
