package com.autocare.platform.service;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.vehicle.VehicleOwner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class NearbyMerchantsController {
    private final ObjectProvider<NearbyMerchants> services;
    public NearbyMerchantsController(ObjectProvider<NearbyMerchants> services){this.services=services;}
    private NearbyMerchants service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"距离服务尚未配置");return s;}
    private static void validate(long id,MultiValueMap<String,String> query){ServiceCatalog.validateId(id);if(!query.isEmpty())throw LocationInput.bad();}
    @PostMapping(value="/api/service/project/{id}/nearby-merchants",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestBody String raw,@RequestParam MultiValueMap<String,String> query){
        var owner=VehicleOwner.from(jwt);validate(id,query);var input=LocationInput.nearby(raw);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service().list(owner,id,input)));
    }
    @PostMapping(value="/api/service/merchant/{id}/distance",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> distance(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestBody String raw,@RequestParam MultiValueMap<String,String> query){
        var owner=VehicleOwner.from(jwt);validate(id,query);var point=LocationInput.point(raw);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service().distance(owner,id,point)));
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> failure(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"距离服务暂不可用，请稍后重试"));}
}
