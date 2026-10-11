package com.autocare.platform.service;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.OperatorActor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/catalog/{kind}")
public class CatalogGovernanceController {
    private final ObjectProvider<CatalogGovernance> services;
    public CatalogGovernanceController(ObjectProvider<CatalogGovernance> services){this.services=services;}
    private CatalogGovernance service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"目录数据库尚未配置");return s;}
    private static int number(MultiValueMap<String,String> p,String key,int fallback){try{return p.containsKey(key)?Integer.parseInt(p.getFirst(key)):fallback;}catch(Exception e){throw CatalogInput.bad();}}
    @GetMapping public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,@PathVariable String kind,@RequestParam MultiValueMap<String,String> params){
        var actor=OperatorActor.from(jwt);CatalogInput.table(kind);
        if(params.entrySet().stream().anyMatch(e->!java.util.Set.of("page","page_size").contains(e.getKey()) || e.getValue().size()!=1 || !e.getValue().get(0).matches("[1-9][0-9]{0,6}")))throw CatalogInput.bad();
        int page=number(params,"page",1),size=number(params,"page_size",20);if(page>1000000 || size>50)throw CatalogInput.bad();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service().list(actor,kind,page,size)));
    }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,@PathVariable String kind,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> params){return save(jwt,kind,0,key,raw,params);}
    @PutMapping(value="/{id}",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt,@PathVariable String kind,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> params){CatalogInput.id(id);return save(jwt,kind,id,key,raw,params);}
    private ResponseEntity<?> save(Jwt jwt,String kind,long id,String key,String raw,MultiValueMap<String,String> params){var actor=OperatorActor.from(jwt);if(!params.isEmpty())throw CatalogInput.bad();WriteIntegrityService.normalizeKey(key);var body=CatalogInput.parse(raw,kind,id>0);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service().save(actor,kind,id,key,body));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"目录服务暂不可用，请用原幂等键重试"));}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> failure(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
}
