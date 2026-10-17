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

@RestController public class PublicCommunityQuestionController {
 private final ObjectProvider<PublicCommunityQuestions> service;
 public PublicCommunityQuestionController(ObjectProvider<PublicCommunityQuestions> service){this.service=service;}
 private PublicCommunityQuestions configured(){var s=service.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"公开问答数据库尚未配置");return s;}
 private ResponseEntity<?> receipt(Object n){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(n);}
 private ResponseEntity<?> ok(Object n){return receipt(ApiResponse.success(n));}
 private void id(long n){com.autocare.platform.service.ServiceCatalog.validateId(n);}
 private void write(long id,String key,MultiValueMap<String,String> q){id(id);InvitationController.query(q,false);WriteIntegrityService.normalizeKey(key);}
 @PostMapping(value="/api/owner/community/questions/cases/{caseId}/publication-preview",consumes="application/json")public ResponseEntity<?> preview(@AuthenticationPrincipal Jwt j,@PathVariable long caseId,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);id(caseId);InvitationController.query(q,false);var b=PublicCommunityQuestions.input(raw,"preview");return ok(configured().preview(a,caseId,b));}
 @GetMapping("/api/owner/community/questions/cases/{caseId}/publications")public ResponseEntity<?> own(@AuthenticationPrincipal Jwt j,@PathVariable long caseId,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);id(caseId);int page=InvitationController.query(q,true);return ok(configured().ownList(a,caseId,page));}
 @PostMapping(value="/api/owner/community/questions/cases/{caseId}/publications",consumes="application/json")public ResponseEntity<?> submit(@AuthenticationPrincipal Jwt j,@PathVariable long caseId,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);write(caseId,key,q);var b=PublicCommunityQuestions.input(raw,"submit");return receipt(configured().submit(a,caseId,key,b));}
 @PostMapping(value="/api/owner/community/question-publications/{id}/withdraw",consumes="application/json")public ResponseEntity<?> withdraw(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);write(id,key,q);var b=PublicCommunityQuestions.input(raw,"withdraw");return receipt(configured().withdraw(a,id,key,b));}
 @GetMapping("/api/admin/community/question-publications")public ResponseEntity<?> pending(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);int page=InvitationController.query(q,true);return ok(configured().pending(a,page));}
 @PostMapping(value="/api/admin/community/question-publications/{id}/moderate",consumes="application/json")public ResponseEntity<?> moderate(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);write(id,key,q);var b=PublicCommunityQuestions.input(raw,"moderate");return receipt(configured().moderate(a,id,key,b));}
 private long vehicle(MultiValueMap<String,String> q,boolean cursor){if(!q.containsKey("vehicle_id")||q.get("vehicle_id").size()!=1||q.keySet().stream().anyMatch(k->!k.equals("vehicle_id")&&!(cursor&&k.equals("cursor"))))throw ReservationInput.bad();return selector(q,"vehicle_id");}
 private long selector(MultiValueMap<String,String> q,String k){if(q.get(k).size()!=1||!q.getFirst(k).matches("[1-9][0-9]{0,15}"))throw ReservationInput.bad();try{long n=Long.parseLong(q.getFirst(k));id(n);return n;}catch(NumberFormatException e){throw ReservationInput.bad();}}
 @GetMapping("/api/community/questions")public ResponseEntity<?> list(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);long v=vehicle(q,true);Long cursor=q.containsKey("cursor")?selector(q,"cursor"):null;return ok(configured().list(a,v,cursor));}
 @GetMapping("/api/community/questions/{publicId}")public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt j,@PathVariable String publicId,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);long v=vehicle(q,false);ExperienceModeration.publicId(publicId);return ok(configured().detail(a,v,publicId));}
 @ExceptionHandler(ResponseStatusException.class)ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class)ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"公开问答暂不可用，请重试原操作"));}
}
