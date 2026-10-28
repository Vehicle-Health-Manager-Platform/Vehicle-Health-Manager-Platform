package com.autocare.platform.order;
import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.OperatorActor;
import com.autocare.platform.vehicle.VehicleOwner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class LicensedKnowledgeController {
 private final ObjectProvider<LicensedKnowledge> services;
 public LicensedKnowledgeController(ObjectProvider<LicensedKnowledge> services){this.services=services;}
 private LicensedKnowledge service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"许可资料库尚未配置");return s;}
 private ResponseEntity<?> ok(Object n){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(n));}
 private ResponseEntity<?> receipt(Object n){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(n);}
 private void id(long n){com.autocare.platform.service.ServiceCatalog.validateId(n);}
 private void empty(MultiValueMap<String,String> q){InvitationController.query(q,false);}
 private void key(String key,MultiValueMap<String,String> q){empty(q);WriteIntegrityService.normalizeKey(key);}
 private long selector(MultiValueMap<String,String> q,String name){if(!q.containsKey(name)||q.get(name).size()!=1||!q.getFirst(name).matches("[1-9][0-9]{0,15}"))throw ReservationInput.bad();try{long n=Long.parseLong(q.getFirst(name));id(n);return n;}catch(NumberFormatException e){throw ReservationInput.bad();}}
 @GetMapping("/api/admin/knowledge/documents")public ResponseEntity<?> admin(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);return ok(service().admin(a,InvitationController.query(q,true)));}
 @GetMapping("/api/admin/knowledge/models")public ResponseEntity<?> models(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);return ok(service().models(a,InvitationController.query(q,true)));}
 @PostMapping(value="/api/admin/knowledge/documents",consumes="application/json")public ResponseEntity<?> create(@AuthenticationPrincipal Jwt j,@RequestHeader(value="Idempotency-Key",required=false)String k,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);key(k,q);return receipt(service().save(a,null,k,KnowledgeInput.config(raw,false)));}
 @PutMapping(value="/api/admin/knowledge/documents/{id}",consumes="application/json")public ResponseEntity<?> update(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String k,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);id(id);key(k,q);return receipt(service().save(a,id,k,KnowledgeInput.config(raw,true)));}
 @PostMapping(value="/api/admin/knowledge/documents/{id}/status",consumes="application/json")public ResponseEntity<?> state(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String k,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);id(id);key(k,q);return receipt(service().status(a,id,k,KnowledgeInput.state(raw)));}
 @GetMapping("/api/knowledge/search")public ResponseEntity<?> search(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);if(q.keySet().stream().anyMatch(k->!java.util.Set.of("vehicle_id","q","cursor").contains(k))||!q.containsKey("q")||q.get("q").size()!=1)throw ReservationInput.bad();long vehicle=selector(q,"vehicle_id");String query=KnowledgeInput.text(new com.fasterxml.jackson.databind.node.TextNode(q.getFirst("q")),80,false);Long cursor=q.containsKey("cursor")?selector(q,"cursor"):null;return ok(service().search(a,vehicle,query,cursor));}
 @GetMapping("/api/knowledge/documents/{reference}")public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt j,@PathVariable String reference,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);if(q.keySet().stream().anyMatch(k->!java.util.Set.of("vehicle_id","chunk").contains(k)))throw ReservationInput.bad();long vehicle=selector(q,"vehicle_id");ExperienceModeration.publicId(reference);Integer chunk=null;if(q.containsKey("chunk")){if(q.get("chunk").size()!=1||!q.getFirst("chunk").matches("0|[1-9][0-9]?"))throw ReservationInput.bad();chunk=Integer.valueOf(q.getFirst("chunk"));if(chunk>63)throw ReservationInput.bad();}return ok(service().detail(a,vehicle,reference,chunk));}
 @PostMapping(value="/api/knowledge/answer",consumes="application/json")public ResponseEntity<?> answer(@AuthenticationPrincipal Jwt j,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);empty(q);return ok(service().answer(a,KnowledgeInput.question(raw)));}
 @ExceptionHandler(ResponseStatusException.class)ResponseEntity<?> error(ResponseStatusException e){int status=e.getStatusCode().value();return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(status==400?40001:status*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class)ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"许可资料服务暂不可用，请重新核验"));}
 @ExceptionHandler(com.autocare.platform.ai.AiUpstreamException.class)ResponseEntity<?> aiUnavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"AI参考暂不可用，可先检索和查看许可资料"));}
}
