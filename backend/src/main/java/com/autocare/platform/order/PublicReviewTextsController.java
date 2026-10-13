package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.OperatorActor;
import com.autocare.platform.service.ServiceCatalog;
import com.autocare.platform.vehicle.VehicleOwner;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController public class PublicReviewTextsController {
    private final ObjectProvider<PublicReviewTexts> services;
    public PublicReviewTextsController(ObjectProvider<PublicReviewTexts> services){this.services=services;}
    private PublicReviewTexts service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"公开文本数据库尚未配置");return s;}
    private static ResponseEntity<?> ok(Object data){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data);}
    private static void scope(long id,MultiValueMap<String,String> q){ServiceCatalog.validateId(id);if(!q.isEmpty())throw ReservationInput.bad();}
    private static int integer(MultiValueMap<String,String> q,String k,int fallback){var v=q.get(k);if(v==null)return fallback;if(v.size()!=1||!v.get(0).matches("[1-9][0-9]{0,6}"))throw ReservationInput.bad();try{return Integer.parseInt(v.get(0));}catch(NumberFormatException e){throw ReservationInput.bad();}}
    private static int[] page(MultiValueMap<String,String> q){if(!Set.of("page","page_size").containsAll(q.keySet()))throw ReservationInput.bad();int p=integer(q,"page",1),s=integer(q,"page_size",20);PublicReviewTexts.page(p,s);return new int[]{p,s};}
    @GetMapping("/api/order/{id}/public-review-text")
    public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(jwt);scope(id,q);return ok(ApiResponse.success(service().detail(a,id)));}
    @PostMapping(value="/api/order/{id}/public-review-text/preview",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> preview(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(jwt);scope(id,q);var b=PublicReviewTextInput.parse(raw);PublicReviewTextInput.fields(b,"public_text");PublicReviewTextInput.redact(b.get("public_text"));return ok(ApiResponse.success(service().preview(a,id,b)));}
    @PutMapping(value="/api/order/{id}/public-review-text",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(jwt);scope(id,q);WriteIntegrityService.normalizeKey(key);var b=PublicReviewTextInput.owner(PublicReviewTextInput.parse(raw));return ok(service().update(a,id,key,b));}
    @GetMapping("/api/admin/review-texts")
    public ResponseEntity<?> pending(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(jwt);var p=page(q);return ok(ApiResponse.success(service().pending(a,p[0],p[1])));}
    @PostMapping(value="/api/admin/review-texts/{id}/moderate",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> moderate(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(jwt);scope(id,q);WriteIntegrityService.normalizeKey(key);var b=PublicReviewTextInput.moderate(PublicReviewTextInput.parse(raw));return ok(service().moderate(a,id,key,b));}
    @GetMapping("/api/service/merchant/{id}/review-texts")
    public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(jwt);ServiceCatalog.validateId(id);var p=page(q);return ok(ApiResponse.success(service().list(a,id,p[0],p[1])));}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> failure(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"公开文本服务暂不可用，请稍后重试"));}
}
