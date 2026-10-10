package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.vehicle.VehicleOwner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Owner-side merchant onboarding: submit/resubmit and read back the current decision. */
@RestController
public class MerchantApplicationController {
    private final ObjectProvider<MerchantOnboarding> services;
    public MerchantApplicationController(ObjectProvider<MerchantOnboarding> services){this.services=services;}
    private MerchantOnboarding service(){var value=services.getIfAvailable();if(value==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"商家入驻数据库尚未配置");return value;}
    private static ResponseEntity<?> ok(Object data){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));}
    /** Write scopes already return the stored ApiResponse envelope: never wrap it a second time. */
    private static ResponseEntity<?> wrote(Object envelope){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(envelope);}
    @PostMapping(value="/api/merchant-applications",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> submit(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> params){
        var owner=VehicleOwner.from(jwt);
        if(!params.isEmpty())throw ReservationInput.bad();
        WriteIntegrityService.normalizeKey(key);
        var body=MerchantOnboardingInput.application(MerchantOnboardingInput.parse(raw));
        return wrote(service().submit(owner,key,body));
    }
    @GetMapping("/api/merchant-applications/mine")
    public ResponseEntity<?> mine(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> params){
        var owner=VehicleOwner.from(jwt);
        if(!params.isEmpty())throw ReservationInput.bad();
        return ok(service().mine(owner));
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"商家入驻服务暂不可用，请使用原幂等键重试"));}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> failure(ResponseStatusException error){int status=error.getStatusCode().value(),code=error instanceof FulfillmentConflict conflict?conflict.code:status==400?40001:status*100;return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(code,error.getReason()));}
}
