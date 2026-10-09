package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.service.ServiceCatalog;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class OrderRedemptionController {
    private static final com.fasterxml.jackson.databind.ObjectMapper INPUT=com.fasterxml.jackson.databind.json.JsonMapper.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static JsonNode parse(String raw){
        if(raw==null || raw.length()>1024)throw ReservationInput.bad();
        try{var body=INPUT.readTree(raw);OrderRedemption.code(body);return body;}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw ReservationInput.bad();}
    }
    private final ObjectProvider<OrderRedemption> services;
    public OrderRedemptionController(ObjectProvider<OrderRedemption> services){this.services=services;}
    private OrderRedemption configured(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"核销数据库尚未配置");return s;}
    private static void query(MultiValueMap<String,String> p){if(!p.isEmpty())throw ReservationInput.bad();}
    private static ResponseEntity<?> saved(Object data){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data);}
    @PostMapping(value="/api/merchant/orders/{id}/redeem",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> redeem(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> p){
        var actor=MerchantActor.from(jwt);query(p);ServiceCatalog.validateId(id);WriteIntegrityService.normalizeKey(key);var body=parse(raw);return saved(configured().redeem(actor,key,id,body));
    }
    @GetMapping("/api/merchant/orders/{id}/redemption") public ResponseEntity<?> merchant(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> p){var actor=MerchantActor.from(jwt);query(p);ServiceCatalog.validateId(id);return saved(ApiResponse.success(configured().detail(actor,id)));}
    @GetMapping("/api/order/{id}/redemption") public ResponseEntity<?> owner(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> p){var actor=VehicleOwner.from(jwt);query(p);ServiceCatalog.validateId(id);return saved(ApiResponse.success(configured().detail(actor,id)));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"核销服务暂不可用，请使用原幂等键重试"));}
    @ExceptionHandler(ResponseStatusException.class)ResponseEntity<?> failure(ResponseStatusException error){
        int status=error.getStatusCode().value(),code=error instanceof FulfillmentConflict c?c.code:status==400?40001:status*100;
        var response=ResponseEntity.status(status).cacheControl(CacheControl.noStore());if(error instanceof OrderRedemption.RateLimited limited)response.header("Retry-After",Integer.toString(limited.retry));
        return response.body(ApiResponse.error(code,error.getReason()));
    }
}
