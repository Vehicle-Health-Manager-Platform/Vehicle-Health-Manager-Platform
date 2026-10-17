package com.autocare.platform.order;
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

@RestController public class AssessmentSnapshotController {
 private final ObjectProvider<AssessmentSnapshots> snapshots;public AssessmentSnapshotController(ObjectProvider<AssessmentSnapshots> snapshots){this.snapshots=snapshots;}
 private AssessmentSnapshots service(){var s=snapshots.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"月度结算数据库尚未配置");return s;}
 private ResponseEntity<?> receipt(Object n){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(n);}
 @GetMapping(AssessmentSnapshots.PATH)public ResponseEntity<?> list(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);int p=InvitationController.query(q,true);return receipt(ApiResponse.success(service().list(a,p)));}
 @PostMapping(value=AssessmentSnapshots.PATH,consumes="application/json")public ResponseEntity<?> create(@AuthenticationPrincipal Jwt j,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);InvitationController.query(q,false);WriteIntegrityService.normalizeKey(key);var b=AssessmentCalculation.input(raw);return receipt(service().create(a,key,b));}
 @GetMapping(AssessmentSnapshots.PATH+"/{id}")public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);com.autocare.platform.service.ServiceCatalog.validateId(id);InvitationController.query(q,false);return receipt(ApiResponse.success(service().detail(a,id)));}
 @GetMapping(AssessmentSnapshots.PATH+"/{id}/facts")public ResponseEntity<?> facts(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);com.autocare.platform.service.ServiceCatalog.validateId(id);int p=InvitationController.query(q,true);return receipt(ApiResponse.success(service().facts(a,id,p)));}
 @ExceptionHandler(ResponseStatusException.class)ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class)ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"月度结算暂不可用，请重试原请求"));}
}
