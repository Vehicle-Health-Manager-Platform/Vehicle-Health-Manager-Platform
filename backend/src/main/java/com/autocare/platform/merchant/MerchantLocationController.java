package com.autocare.platform.merchant;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.service.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MerchantLocationController {
    private final ObjectProvider<MerchantLocation> services;
    public MerchantLocationController(ObjectProvider<MerchantLocation> services){this.services=services;}
    private MerchantLocation service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"门店位置服务尚未配置");return s;}
    private static void noQuery(MultiValueMap<String,String> p){if(!p.isEmpty())throw LocationInput.bad();}
    @GetMapping("/api/merchant/location")
    public ResponseEntity<?> read(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> params){
        var actor=MerchantActor.from(jwt);noQuery(params);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service().read(actor)));
    }
    @PutMapping(value="/api/merchant/location",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> params){
        var actor=MerchantActor.from(jwt);noQuery(params);WriteIntegrityService.normalizeKey(key);var input=LocationInput.store(raw);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service().update(actor,key,input));
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> failure(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"门店位置暂不可用，请使用原提交键重试"));}
}
