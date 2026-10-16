package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.file.*;
import com.autocare.platform.service.*;
import com.autocare.platform.vehicle.VehicleOwner;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController public class ServiceWorkspaceController {
 private final ObjectProvider<ServiceWorkbench> benches;private final ObjectProvider<OrderArchiveGrants> grants;private final PrivateFileAccessService files;private final String appId;
 public ServiceWorkspaceController(ObjectProvider<ServiceWorkbench> benches,ObjectProvider<OrderArchiveGrants> grants,PrivateFileAccessService files,@Value("${WECHAT_APP_ID:}")String appId){this.benches=benches;this.grants=grants;this.files=files;this.appId=appId;}
 private <T>T configured(ObjectProvider<T> p){var n=p.getIfAvailable();if(n==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"服务工作台数据库尚未配置");return n;}
 private ResponseEntity<?> ok(Object data){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));}
 private void ids(long... ids){for(long id:ids)ServiceCatalog.validateId(id);}
 private int page(MultiValueMap<String,String> q){return InvitationController.query(q,true);}
 private ResponseEntity<?> bench(Object actor,MultiValueMap<String,String> q){var rest=new LinkedMultiValueMap<String,String>(q);String bucket=null;if(rest.containsKey("bucket")){if(rest.get("bucket").size()!=1||!ServiceWorkbench.BUCKETS.contains(rest.getFirst("bucket")))throw ReservationInput.bad();bucket=rest.remove("bucket").get(0);}return ok(configured(benches).read(actor,bucket,page(rest)));}
 @GetMapping("/api/merchant/workbench") public ResponseEntity<?> merchantBench(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){return bench(MerchantActor.from(j),q);}
 @GetMapping("/api/tech/workbench") public ResponseEntity<?> technicianBench(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){return bench(TechnicianActor.from(j,appId),q);}
 @GetMapping("/api/order/{id}/archive-grants") public ResponseEntity<?> candidates(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);ids(id);return ok(configured(grants).candidates(a,id,page(q)));}
 @PutMapping(value="/api/order/{id}/archive-grants/{archive}",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> grant(@AuthenticationPrincipal Jwt j,@PathVariable long id,@PathVariable long archive,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String raw,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);ids(id,archive);InvitationController.query(q,false);WriteIntegrityService.normalizeKey(key);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(configured(grants).set(a,id,archive,key,OrderArchiveGrants.input(raw)));}
 @GetMapping("/api/merchant/orders/{id}/service-context") public ResponseEntity<?> merchantContext(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestParam MultiValueMap<String,String> q){var a=MerchantActor.from(j);ids(id);return ok(configured(grants).context(a,id,page(q)));}
 @GetMapping("/api/tech/orders/{id}/service-context") public ResponseEntity<?> technicianContext(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestParam MultiValueMap<String,String> q){var a=TechnicianActor.from(j,appId);ids(id);return ok(configured(grants).context(a,id,page(q)));}
 private ResponseEntity<?> sign(Supplier<FileMetadataRepository.Actor> check,long file){var actor=check.get();var signed=files.sign(actor,file);if(!actor.equals(check.get()))throw ReservationStore.missing();return ok(Map.of("url",signed.url(),"expires_at",signed.expiresAt()));}
 @GetMapping("/api/order/{id}/archive-grants/{archive}/files/{file}/preview") public ResponseEntity<?> preview(@AuthenticationPrincipal Jwt j,@PathVariable long id,@PathVariable long archive,@PathVariable long file,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(j);ids(id,archive,file);InvitationController.query(q,false);var s=configured(grants);return sign(()->s.ownerPreview(a,id,archive,file),file);}
 @GetMapping("/api/merchant/orders/{id}/service-context/archives/{archive}/files/{file}/access") public ResponseEntity<?> merchantFile(@AuthenticationPrincipal Jwt j,@PathVariable long id,@PathVariable long archive,@PathVariable long file,@RequestParam MultiValueMap<String,String> q){var a=MerchantActor.from(j);ids(id,archive,file);InvitationController.query(q,false);var s=configured(grants);return sign(()->s.fileOwner(a,id,archive,file),file);}
 @GetMapping("/api/tech/orders/{id}/service-context/archives/{archive}/files/{file}/access") public ResponseEntity<?> techFile(@AuthenticationPrincipal Jwt j,@PathVariable long id,@PathVariable long archive,@PathVariable long file,@RequestParam MultiValueMap<String,String> q){var a=TechnicianActor.from(j,appId);ids(id,archive,file);InvitationController.query(q,false);var s=configured(grants);return sign(()->s.fileOwner(a,id,archive,file),file);}
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"服务资料暂不可用，请重试原操作"));}
}
