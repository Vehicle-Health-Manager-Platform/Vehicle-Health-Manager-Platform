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

@RestController public class PlanBookingContextController {
    private final ObjectProvider<PlanBookingContext> services;
    public PlanBookingContextController(ObjectProvider<PlanBookingContext> services){this.services=services;}
    @PostMapping(value="/api/order/plan-booking-context",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> read(@AuthenticationPrincipal Jwt jwt,@RequestBody String raw,@RequestParam MultiValueMap<String,String> query){var owner=VehicleOwner.from(jwt);if(!query.isEmpty())throw ReservationInput.bad();var in=PlanBookingContext.input(raw);var service=services.getIfAvailable();if(service==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"方案预约数据库尚未配置");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.read(owner,in)));}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> failure(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"方案预约暂不可用，请稍后重试"));}
}
