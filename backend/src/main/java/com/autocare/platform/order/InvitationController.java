package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.vehicle.VehicleOwner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController public class InvitationController {
    private final ObjectProvider<Invitations> services;
    public InvitationController(ObjectProvider<Invitations> services){this.services=services;}
    Invitations service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"邀请数据库尚未配置");return s;}
    static ResponseEntity<?> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    static int query(MultiValueMap<String,String> q,boolean paged){for(var e:q.entrySet())if(!paged||!java.util.Set.of("page","page_size").contains(e.getKey())||e.getValue().size()!=1)throw InvitationInput.bad();int page=1;if(q.containsKey("page")){String p=q.getFirst("page");if(p==null||!p.matches("[1-9][0-9]{0,6}"))throw InvitationInput.bad();page=Integer.parseInt(p);if(page>1000000)throw InvitationInput.bad();}if(q.containsKey("page_size")&&!"20".equals(q.getFirst("page_size")))throw InvitationInput.bad();return page;}
    @GetMapping("/api/invite/qrcode") public ResponseEntity<?> code(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(jwt);query(q,false);return ok(ApiResponse.success(service().code(a)));}
    @PostMapping(value="/api/invite/code",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw){var a=VehicleOwner.from(jwt);query(q,false);WriteIntegrityService.normalizeKey(key);return ok(service().create(a,key,InvitationInput.empty(raw)));}
    @GetMapping("/api/invite/records") public ResponseEntity<?> records(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(jwt);int page=query(q,true);return ok(ApiResponse.success(service().records(a,page)));}
    @GetMapping("/api/invite/progress") public ResponseEntity<?> progress(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(jwt);query(q,false);return ok(ApiResponse.success(service().progress(a)));}
    @GetMapping("/api/invite/registration") public ResponseEntity<?> registration(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(jwt);query(q,false);return ok(ApiResponse.success(service().registration(a)));}
    @GetMapping("/api/merchant/invite/code") public ResponseEntity<?> staffCode(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=MerchantActor.from(jwt);query(q,false);return ok(ApiResponse.success(service().code(a)));}
    @PostMapping(value="/api/merchant/invite/code",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> staffCreate(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw){var a=MerchantActor.from(jwt);query(q,false);WriteIntegrityService.normalizeKey(key);return ok(service().create(a,key,InvitationInput.empty(raw)));}
    @GetMapping("/api/merchant/invite/records") public ResponseEntity<?> staffRecords(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=MerchantActor.from(jwt);int page=query(q,true);return ok(ApiResponse.success(service().records(a,page)));}
    @GetMapping("/api/merchant/invite/progress") public ResponseEntity<?> staffProgress(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=MerchantActor.from(jwt);query(q,false);return ok(ApiResponse.success(service().progress(a)));}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"邀请服务暂不可用，请重试"));}
}
