package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.OperatorActor;
import com.autocare.platform.service.ServiceCatalog;
import com.autocare.platform.vehicle.VehicleOwner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController public class ExperienceModerationController {
    private final ObjectProvider<ExperienceModeration> services;
    public ExperienceModerationController(ObjectProvider<ExperienceModeration> services){this.services=services;}
    private ExperienceModeration service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"经验审核数据库尚未配置");return s;}
    private static ResponseEntity<?> ok(Object v){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(v);}
    private static void query(MultiValueMap<String,String> p,java.util.Set<String> keys){if(p.entrySet().stream().anyMatch(e->!keys.contains(e.getKey()) || e.getValue().size()!=1 || !e.getValue().get(0).matches("[1-9][0-9]{0,15}")))throw ReservationInput.bad();}
    @GetMapping("/api/admin/experience-cards")
    public ResponseEntity<?> pending(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> p){var a=OperatorActor.from(jwt);query(p,java.util.Set.of("page","page_size"));
        try{int page=p.containsKey("page")?Integer.parseInt(p.getFirst("page")):1,size=p.containsKey("page_size")?Integer.parseInt(p.getFirst("page_size")):20;ExperienceCards.page(page,size);return ok(ApiResponse.success(service().pending(a,page,size)));}catch(NumberFormatException e){throw ReservationInput.bad();}}
    @PostMapping(value="/api/admin/experience-cards/{id}/moderate",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> moderate(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> p){var a=OperatorActor.from(jwt);if(!p.isEmpty())throw ReservationInput.bad();ServiceCatalog.validateId(id);WriteIntegrityService.normalizeKey(key);var b=ExperienceModerationInput.parse(raw);return ok(service().moderate(a,id,key,b));}
    @GetMapping("/api/community/experiences")
    public ResponseEntity<?> experiences(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> p){var owner=VehicleOwner.from(jwt);query(p,java.util.Set.of("vehicle_id","cursor"));if(!p.containsKey("vehicle_id"))throw ReservationInput.bad();
        try{long vehicle=Long.parseLong(p.getFirst("vehicle_id"));Long cursor=p.containsKey("cursor")?Long.parseLong(p.getFirst("cursor")):null;ServiceCatalog.validateId(vehicle);if(cursor!=null)ServiceCatalog.validateId(cursor);return ok(ApiResponse.success(service().experiences(owner,vehicle,cursor)));}catch(NumberFormatException e){throw ReservationInput.bad();}}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"经验服务暂不可用，请使用原请求重试"));}
    @ExceptionHandler(ResponseStatusException.class)ResponseEntity<?> failure(ResponseStatusException e){int s=e.getStatusCode().value(),code=e instanceof FulfillmentConflict c?c.code:s==400?40001:s*100;return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(code,e.getReason()));}
}
