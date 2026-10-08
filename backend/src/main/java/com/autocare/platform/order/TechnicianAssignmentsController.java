package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class TechnicianAssignmentsController {
    private final ObjectProvider<TechnicianAssignments> services;
    private final String appId;
    public TechnicianAssignmentsController(ObjectProvider<TechnicianAssignments> services,@Value("${WECHAT_APP_ID:}")String appId){this.services=services;this.appId=appId;}
    private TechnicianAssignments configured(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"派工数据库尚未配置");return s;}
    private static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"派工或工单参数无效");}
    private static void fields(MultiValueMap<String,String> p,String... allowed){if(!Set.of(allowed).containsAll(p.keySet()))throw bad();}
    private static String single(MultiValueMap<String,String> p,String name){var v=p.get(name);if(v==null)return null;if(v.size()!=1 || v.get(0)==null || v.get(0).isBlank())throw bad();return v.get(0);}
    private static int number(MultiValueMap<String,String> p,String name,int fallback,int max){var v=single(p,name);if(v==null)return fallback;if(!v.matches("[1-9][0-9]{0,6}"))throw bad();int n=Integer.parseInt(v);if(n>max)throw bad();return n;}
    private static ResponseEntity<?> ok(Object data){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));}
    private static ResponseEntity<?> saved(JsonNode data){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data);}
    @GetMapping("/api/merchant/technicians") public ResponseEntity<?> candidates(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> p){
        var actor=MerchantActor.from(jwt);fields(p,"page","page_size");int page=number(p,"page",1,1000000),size=number(p,"page_size",20,100);
        return ok(configured().candidates(actor,page,size));
    }
    @GetMapping("/api/merchant/orders/{id}/assignment") public ResponseEntity<?> merchantDetail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> p){
        var actor=MerchantActor.from(jwt);fields(p);ServiceCatalog.validateId(id);return ok(configured().merchantDetail(actor,id));
    }
    @PostMapping("/api/merchant/orders/{id}/assign") public ResponseEntity<?> assign(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> p){
        var actor=MerchantActor.from(jwt);fields(p);ServiceCatalog.validateId(id);WriteIntegrityService.normalizeKey(key);
        if(body==null || !body.isObject() || body.size()!=1 || !body.has("technician_id") || !body.get("technician_id").isIntegralNumber() || !body.get("technician_id").canConvertToLong())throw bad();
        long staff=body.get("technician_id").longValue();ServiceCatalog.validateId(staff);return saved(configured().assign(actor,key,id,staff));
    }
    @GetMapping("/api/tech/orders") public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> p){
        var actor=TechnicianActor.from(jwt,appId);fields(p,"page","page_size","assignment_status");int page=number(p,"page",1,1000000),size=number(p,"page_size",20,100);
        String status=single(p,"assignment_status");if(status!=null && !Set.of("ASSIGNED","ACCEPTED").contains(status))throw bad();return ok(configured().list(actor,status,page,size));
    }
    @GetMapping("/api/tech/orders/{id}") public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam MultiValueMap<String,String> p){
        var actor=TechnicianActor.from(jwt,appId);fields(p);ServiceCatalog.validateId(id);return ok(configured().detail(actor,id));
    }
    @PostMapping("/api/tech/orders/{id}/accept") public ResponseEntity<?> accept(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody JsonNode body,@RequestParam MultiValueMap<String,String> p){
        var actor=TechnicianActor.from(jwt,appId);fields(p);ServiceCatalog.validateId(id);WriteIntegrityService.normalizeKey(key);
        if(body==null || !body.isObject() || !body.isEmpty())throw bad();return saved(configured().accept(actor,key,id));
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"派工服务暂不可用，请使用原幂等键重试"));}
    @ExceptionHandler(ResponseStatusException.class)ResponseEntity<?> failure(ResponseStatusException error){int status=error.getStatusCode().value(),code=error instanceof FulfillmentConflict conflict?conflict.code:status==400?40001:status*100;return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(code,error.getReason()));}
}
