package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.vehicle.VehicleOwner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController public class AiServicePlansController {
    private final ObjectProvider<AiServicePlans> services;
    public AiServicePlansController(ObjectProvider<AiServicePlans> services){this.services=services;}
    private AiServicePlans service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"结构化项目数据库尚未配置");return s;}
    private static void query(MultiValueMap<String,String> q){if(!q.isEmpty())throw ReservationInput.bad();}
    @GetMapping("/api/ai/service-plan/catalog") public ResponseEntity<?> catalog(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var owner=VehicleOwner.from(jwt);query(q);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service().catalog(owner)));}
    @PostMapping(value="/api/ai/service-plan",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> generate(@AuthenticationPrincipal Jwt jwt,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var owner=VehicleOwner.from(jwt);query(q);var in=AiServicePlans.input(raw);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service().generate(owner,in)));}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> failure(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"结构化项目建议暂不可用，请稍后重试"));}
}
