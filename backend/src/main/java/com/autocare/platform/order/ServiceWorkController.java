package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.file.PrivateFileAccessService;
import com.autocare.platform.service.MerchantActor;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ServiceWorkController {
    private final ObjectProvider<ServiceWork> services;
    private final PrivateFileAccessService files;
    private final String appId;
    public ServiceWorkController(ObjectProvider<ServiceWork> services,PrivateFileAccessService files,@Value("${WECHAT_APP_ID:}")String appId){this.services=services;this.files=files;this.appId=appId;}
    private ServiceWork configured(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"施工数据库尚未配置");return s;}
    private static void query(MultiValueMap<String,String> p){if(!p.isEmpty())throw ReservationInput.bad();}
    private static ResponseEntity<?> saved(Object data){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data);}
    @PostMapping("/api/check/protection/upload") public ResponseEntity<?> protect(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> p){
        var actor=MerchantActor.from(jwt);query(p);WriteIntegrityService.normalizeKey(key);ServiceWorkInput.protection(body);return saved(configured().protect(actor,key,body));
    }
    @PostMapping("/api/tech/report/submit") public ResponseEntity<?> submit(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> p){
        var actor=TechnicianActor.from(jwt,appId);query(p);WriteIntegrityService.normalizeKey(key);ServiceWorkInput.report(body);return saved(configured().submit(actor,key,body));
    }
    @PostMapping("/api/tech/sign") public ResponseEntity<?> sign(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> p){
        var actor=TechnicianActor.from(jwt,appId);query(p);WriteIntegrityService.normalizeKey(key);ServiceWorkInput.sign(body);return saved(configured().sign(actor,key,body));
    }
    @GetMapping("/api/merchant/orders/{id}/work") public ResponseEntity<?> merchantDetail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> p){var actor=MerchantActor.from(jwt);query(p);com.autocare.platform.service.ServiceCatalog.validateId(id);return saved(ApiResponse.success(configured().detail(actor,id)));}
    @GetMapping("/api/tech/orders/{id}/work") public ResponseEntity<?> techDetail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> p){var actor=TechnicianActor.from(jwt,appId);query(p);com.autocare.platform.service.ServiceCatalog.validateId(id);return saved(ApiResponse.success(configured().detail(actor,id)));}
    private ResponseEntity<?> access(Object actor,long id,long file){com.autocare.platform.service.ServiceCatalog.validateId(id);com.autocare.platform.service.ServiceCatalog.validateId(file);var owner=configured().fileOwner(actor,id,file);var signed=files.sign(owner,file);return saved(ApiResponse.success(Map.of("url",signed.url(),"expires_at",signed.expiresAt())));}
    @GetMapping("/api/merchant/orders/{id}/work/files/{file}/access") public ResponseEntity<?> merchantFile(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@PathVariable long file,@RequestParam MultiValueMap<String,String> p){var actor=MerchantActor.from(jwt);query(p);return access(actor,id,file);}
    @GetMapping("/api/tech/orders/{id}/work/files/{file}/access") public ResponseEntity<?> techFile(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@PathVariable long file,@RequestParam MultiValueMap<String,String> p){var actor=TechnicianActor.from(jwt,appId);query(p);return access(actor,id,file);}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"施工服务暂不可用，请使用原幂等键重试"));}
    @ExceptionHandler(ResponseStatusException.class)ResponseEntity<?> failure(ResponseStatusException error){int status=error.getStatusCode().value(),code=error instanceof FulfillmentConflict c?c.code:status==400?40001:status*100;return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(code,error.getReason()));}
}
