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

@RestController
public class OrderReviewsController {
    private final ObjectProvider<OrderReviews> services;
    public OrderReviewsController(ObjectProvider<OrderReviews> services){this.services=services;}
    private OrderReviews configured(){var service=services.getIfAvailable();if(service==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"评价数据库尚未配置");return service;}
    private static void query(MultiValueMap<String,String> p){if(!p.isEmpty())throw ReservationInput.bad();}
    private static ResponseEntity<?> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    @PostMapping(value="/api/order/review",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> submit(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> params){
        var owner=VehicleOwner.from(jwt);query(params);WriteIntegrityService.normalizeKey(key);var body=OrderReviewInput.parse(raw);return ok(configured().submit(owner,key,body));
    }
    @GetMapping("/api/order/{id}/review")
    public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> params){var owner=VehicleOwner.from(jwt);query(params);ServiceCatalog.validateId(id);return ok(ApiResponse.success(configured().detail(owner,id)));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"评价服务暂不可用，请使用原请求重试"));}
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> failure(ResponseStatusException e){int status=e.getStatusCode().value(),code=e instanceof FulfillmentConflict c?c.code:status==400?40001:status*100;return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(code,e.getReason()));}
}
