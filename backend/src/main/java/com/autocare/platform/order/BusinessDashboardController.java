package com.autocare.platform.order;
import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.gateway.identity.OperatorActor;
import com.autocare.platform.service.MerchantActor;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController public class BusinessDashboardController {
 private final ObjectProvider<BusinessDashboard> dashboards;public BusinessDashboardController(ObjectProvider<BusinessDashboard> dashboards){this.dashboards=dashboards;}
 private BusinessDashboard service(){var s=dashboards.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"经营看板数据库尚未配置");return s;}
 private record Selection(long shop,String month){}
 private Selection query(MultiValueMap<String,String> q,boolean admin){for(var e:q.entrySet())if(!(admin?Set.of("period_month","merchant_id"):Set.of("period_month")).contains(e.getKey())||e.getValue().size()!=1)throw ReservationInput.bad();String month=q.getFirst("period_month");AssessmentCalculation.month(month);long shop=0;if(admin){String id=q.getFirst("merchant_id");if(id==null||!id.matches("[1-9][0-9]{0,15}"))throw ReservationInput.bad();try{shop=Long.parseLong(id);com.autocare.platform.service.ServiceCatalog.validateId(shop);}catch(NumberFormatException e){throw ReservationInput.bad();}}return new Selection(shop,month);}
 private ResponseEntity<?> ok(Object n){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(n));}
 @GetMapping("/api/admin/business/merchants")public ResponseEntity<?> merchants(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);int p=InvitationController.query(q,true);return ok(service().merchants(a,p));}
 @GetMapping("/api/admin/business/dashboard")public ResponseEntity<?> admin(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=OperatorActor.from(j);var s=query(q,true);return ok(service().dashboard(a,s.shop(),s.month()));}
 @GetMapping("/api/merchant/business/dashboard")public ResponseEntity<?> merchant(@AuthenticationPrincipal Jwt j,@RequestParam MultiValueMap<String,String> q){var a=MerchantActor.from(j);if(!a.manager())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅店长可读取本店经营看板");var s=query(q,false);return ok(service().dashboard(a,s.month()));}
 @ExceptionHandler(ResponseStatusException.class)ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
 @ExceptionHandler(org.springframework.dao.DataAccessException.class)ResponseEntity<?> unavailable(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"经营来源暂不可用，请刷新重试"));}
}
