package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.service.ServiceCatalog;
import com.autocare.platform.vehicle.VehicleOwner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController public class ReviewSummariesController {
    private final ObjectProvider<ReviewSummaries> services;
    public ReviewSummariesController(ObjectProvider<ReviewSummaries> services){this.services=services;}
    private ReviewSummaries service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"评价总结数据库尚未配置");return s;}
    private static void scope(long id,MultiValueMap<String,String> q){ServiceCatalog.validateId(id);if(!q.isEmpty())throw ReservationInput.bad();}
    private static ResponseEntity<?> ok(Object data){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data);}
    @GetMapping("/api/order/{id}/review-summary-consent")
    public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> q){var owner=VehicleOwner.from(jwt);scope(id,q);return ok(ApiResponse.success(service().detail(owner,id)));}
    @PutMapping(value="/api/order/{id}/review-summary-consent",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var owner=VehicleOwner.from(jwt);scope(id,q);WriteIntegrityService.normalizeKey(key);var b=ReviewSummaries.input(raw);return ok(service().update(owner,id,key,b));}
    @PostMapping(value="/api/service/merchant/{id}/review-summary",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> summary(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var owner=VehicleOwner.from(jwt);scope(id,q);PublicReviewTextInput.fields(PublicReviewTextInput.parse(raw));return ok(ApiResponse.success(service().summarize(owner,id)));}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> failure(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"评价总结暂不可用，请稍后重试"));}
}
