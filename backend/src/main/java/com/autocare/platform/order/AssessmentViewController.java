package com.autocare.platform.order;
import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.gateway.identity.OperatorActor;
import com.autocare.platform.service.MerchantActor;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController public class AssessmentViewController {
 private final ObjectProvider<AssessmentViews> views;public AssessmentViewController(ObjectProvider<AssessmentViews> views){this.views=views;}
 private AssessmentViews service(){var s=views.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"月结读取数据库尚未配置");return s;}
 private record Query(long shop,String month,int page){}
 private Query query(MultiValueMap<String,String> q,boolean admin,boolean paged){var keys=new HashSet<>(List.of("period_month"));if(admin)keys.add("merchant_id");if(paged)keys.addAll(List.of("page","page_size"));for(var e:q.entrySet())if(!keys.contains(e.getKey())||e.getValue().size()!=1)throw ReservationInput.bad();String month=q.getFirst("period_month");AssessmentCalculation.month(month);long shop=0;if(admin){String id=q.getFirst("merchant_id");if(id==null||!id.matches("[1-9][0-9]{0,15}"))throw ReservationInput.bad();try{shop=Long.parseLong(id);com.autocare.platform.service.ServiceCatalog.validateId(shop);}catch(NumberFormatException e){throw ReservationInput.bad();}}var pageQuery=new LinkedMultiValueMap<String,String>();for(String k:List.of("page","page_size"))if(q.containsKey(k))pageQuery.put(k,q.get(k));return new Query(shop,month,InvitationController.query(pageQuery,paged));}
 private MerchantActor manager(Jwt j){var a=MerchantActor.from(j);if(!a.manager())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅店长可读取本店月度考核");return a;}
 private ResponseEntity<?> ok(Object n){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(n));}
 @GetMapping("/api/admin/assessment/month")public ResponseEntity<?> adminMonth(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);var p=query(q,true,false);return ok(service().context(a,p.shop(),p.month()));}
 @GetMapping("/api/admin/assessment/history")public ResponseEntity<?> adminHistory(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);var p=query(q,true,true);return ok(service().history(a,p.shop(),p.month(),p.page()));}
 @GetMapping("/api/merchant/assessment/month")public ResponseEntity<?> merchantMonth(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=manager(j);var p=query(q,false,false);return ok(service().context(a,p.month()));}
 @GetMapping("/api/merchant/assessment/history")public ResponseEntity<?> merchantHistory(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=manager(j);var p=query(q,false,true);return ok(service().history(a,p.month(),p.page()));}
 @GetMapping("/api/merchant/assessment/snapshots/{id}")public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestParam MultiValueMap<String,String> q){var a=manager(j);com.autocare.platform.service.ServiceCatalog.validateId(id);InvitationController.query(q,false);return ok(service().detail(a,id));}
 @GetMapping("/api/merchant/assessment/snapshots/{id}/facts")public ResponseEntity<?> facts(@AuthenticationPrincipal Jwt j,@PathVariable long id,@RequestParam MultiValueMap<String,String> q){var a=manager(j);com.autocare.platform.service.ServiceCatalog.validateId(id);int p=InvitationController.query(q,true);return ok(service().facts(a,id,p));}
 @GetMapping("/api/merchant/assessment/staff")public ResponseEntity<?> staff(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=MerchantActor.from(j);var p=query(q,false,false);return ok(service().staff(a,p.month()));}
 @ExceptionHandler(ResponseStatusException.class)ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class)ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"月结读取暂不可用，请刷新重试"));}
}
