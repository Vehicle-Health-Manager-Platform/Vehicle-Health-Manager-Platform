package com.autocare.platform.service;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MerchantQuotesController {
    private final ObjectProvider<MerchantQuotes> services;
    public MerchantQuotesController(ObjectProvider<MerchantQuotes> services) {this.services=services;}
    private MerchantQuotes service() {var result=services.getIfAvailable();if(result==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"报价数据库尚未配置");return result;}
    private ResponseEntity<?> response(Object data) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));}
    @GetMapping("/api/service/project/{id}/merchants")
    public ResponseEntity<?> owners(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam(defaultValue="price_asc") String sort,
        @RequestParam(defaultValue="1") int page,@RequestParam(name="page_size",defaultValue="20") int size) {
        var owner=VehicleOwner.from(jwt);ServiceCatalog.validateId(id);ServiceCatalog.validate(null,page,size);
        if(!java.util.Set.of("price_asc","price_desc").contains(sort))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"报价排序无效");
        return response(service().ownerList(owner,id,sort,page,size));
    }
    @GetMapping("/api/merchant/standard-projects")
    public ResponseEntity<?> standards(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) Integer category,
        @RequestParam(defaultValue="1") int page,@RequestParam(name="page_size",defaultValue="20") int size) {
        var actor=MerchantActor.from(jwt);ServiceCatalog.validate(category,page,size);return response(service().standards(actor,category,page,size));
    }
    @GetMapping("/api/merchant/projects")
    public ResponseEntity<?> own(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,@RequestParam(name="page_size",defaultValue="20") int size) {
        var actor=MerchantActor.from(jwt);ServiceCatalog.validate(null,page,size);return response(service().ownList(actor,page,size));
    }
    @PostMapping("/api/merchant/projects")
    public ResponseEntity<?> save(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody JsonNode body) {
        var actor=MerchantActor.from(jwt);WriteIntegrityService.normalizeKey(key);var input=QuoteInput.parse(body);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service().save(actor,key,input));
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<?> database() {return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"报价服务暂不可用，请稍后重试"));}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException error) {int code=error.getStatusCode().value();return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).body(ApiResponse.error(code==400?40001:code*100,error.getReason()));}
}
