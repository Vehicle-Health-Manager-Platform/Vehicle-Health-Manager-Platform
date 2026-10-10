package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.file.PrivateFileAccessService;
import com.autocare.platform.gateway.identity.OperatorActor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Operator-side merchant onboarding review, quota maintenance and controlled file access. */
@RestController
public class MerchantOnboardingAdminController {
    private final ObjectProvider<MerchantOnboarding> services;
    private final PrivateFileAccessService files;
    public MerchantOnboardingAdminController(ObjectProvider<MerchantOnboarding> services,PrivateFileAccessService files){this.services=services;this.files=files;}
    private MerchantOnboarding service(){var value=services.getIfAvailable();if(value==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"商家入驻数据库尚未配置");return value;}
    private static ResponseEntity<?> ok(Object data){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));}
    /** Write scopes already return the stored ApiResponse envelope: never wrap it a second time. */
    private static ResponseEntity<?> wrote(Object envelope){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(envelope);}
    private static void query(MultiValueMap<String,String> params,java.util.Set<String> keys){
        if(params.entrySet().stream().anyMatch(e->!keys.contains(e.getKey()) || e.getValue().size()!=1 || !e.getValue().get(0).matches("[1-9][0-9]{0,15}")))throw ReservationInput.bad();
    }
    @GetMapping("/api/admin/merchant-applications")
    public ResponseEntity<?> pending(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> params){
        var actor=OperatorActor.from(jwt);query(params,java.util.Set.of("page","page_size"));
        try{
            int page=params.containsKey("page")?Integer.parseInt(params.getFirst("page")):1;
            int size=params.containsKey("page_size")?Integer.parseInt(params.getFirst("page_size")):20;
            MerchantOnboardingInput.page(page,size);
            return ok(service().pending(actor,page,size));
        }catch(NumberFormatException error){throw ReservationInput.bad();}
    }
    @GetMapping("/api/admin/merchant-applications/{id}")
    public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> params){
        var actor=OperatorActor.from(jwt);if(!params.isEmpty())throw ReservationInput.bad();
        MerchantOnboardingInput.id(id);
        return ok(service().detail(actor,id));
    }
    @GetMapping("/api/admin/merchant-applications/{id}/files/{file}/access")
    public ResponseEntity<?> access(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@PathVariable long file,@RequestParam MultiValueMap<String,String> params){
        var actor=OperatorActor.from(jwt);if(!params.isEmpty())throw ReservationInput.bad();
        MerchantOnboardingInput.id(id);MerchantOnboardingInput.id(file);
        var signed=files.sign(service().fileOwner(actor,id,file),file);
        return ok(java.util.Map.of("url",signed.url(),"expires_at",signed.expiresAt()));
    }
    @PostMapping(value="/api/admin/merchant-applications/{id}/moderate",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> moderate(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> params){
        var actor=OperatorActor.from(jwt);if(!params.isEmpty())throw ReservationInput.bad();
        WriteIntegrityService.normalizeKey(key);
        var body=MerchantOnboardingInput.moderation(MerchantOnboardingInput.parse(raw));
        return wrote(service().moderate(actor,id,key,body));
    }
    @GetMapping("/api/admin/merchant-quotas")
    public ResponseEntity<?> quotas(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> params){
        var actor=OperatorActor.from(jwt);if(!params.isEmpty())throw ReservationInput.bad();
        return ok(service().quotas(actor));
    }
    @PutMapping(value="/api/admin/merchant-quotas",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> setQuota(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> params){
        var actor=OperatorActor.from(jwt);if(!params.isEmpty())throw ReservationInput.bad();
        WriteIntegrityService.normalizeKey(key);
        var body=MerchantOnboardingInput.quota(MerchantOnboardingInput.parse(raw));
        return wrote(service().setQuota(actor,key,body));
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"商家入驻服务暂不可用，请使用原幂等键重试"));}
    @ExceptionHandler(com.autocare.platform.file.UploadException.class) ResponseEntity<?> upload(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"资质文件服务暂不可用"));}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> failure(ResponseStatusException error){int status=error.getStatusCode().value(),code=error instanceof FulfillmentConflict conflict?conflict.code:status==400?40001:status*100;return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(code,error.getReason()));}
}
