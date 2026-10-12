package com.autocare.platform.service;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.vehicle.VehicleOwner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MerchantDiscoveryController {
    private final ObjectProvider<MerchantDiscovery> services;
    public MerchantDiscoveryController(ObjectProvider<MerchantDiscovery> services){this.services=services;}
    @GetMapping("/api/service/merchant/{id}")
    public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam(value="project_id",required=false) Long project,
                                   @RequestParam(defaultValue="1") int page,@RequestParam(value="page_size",defaultValue="20") int size){
        var owner=VehicleOwner.from(jwt);ServiceCatalog.validateId(id);ServiceCatalog.validate(null,page,size);if(project!=null)ServiceCatalog.validateId(project);
        var service=services.getIfAvailable();if(service==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"门店详情服务尚未配置");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.detail(owner,id,project,page,size)));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> failure(ResponseStatusException e){int status=e.getStatusCode().value();return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(status==400?40001:status*100,e.getReason()));}
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"门店详情暂不可用，请稍后重试"));}
}
