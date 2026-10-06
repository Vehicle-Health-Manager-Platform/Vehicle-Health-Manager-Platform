package com.autocare.platform.service;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.vehicle.VehicleOwner;
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
public class ServiceCatalogController {
    private final ObjectProvider<ServiceCatalog> catalogs;
    public ServiceCatalogController(ObjectProvider<ServiceCatalog> catalogs) { this.catalogs=catalogs; }
    private ServiceCatalog catalog() {
        var result=catalogs.getIfAvailable();
        if(result==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"服务项目数据库尚未配置");
        return result;
    }
    @GetMapping("/api/service/projects")
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,
        @RequestParam(required=false) Integer category, @RequestParam(defaultValue="1") int page,
        @RequestParam(name="page_size",defaultValue="20") int size) {
        var owner=VehicleOwner.from(jwt); ServiceCatalog.validate(category,page,size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(catalog().list(owner,category,page,size)));
    }
    @GetMapping("/api/service/project/{id}")
    public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id) {
        var owner=VehicleOwner.from(jwt); ServiceCatalog.validateId(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(catalog().detail(owner,id)));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
            .body(ApiResponse.error(50300,"服务项目暂不可用，请稍后重试"));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException error) {
        int status=error.getStatusCode().value();
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body(ApiResponse.error(status==400?40001:status*100,error.getReason()));
    }
}
