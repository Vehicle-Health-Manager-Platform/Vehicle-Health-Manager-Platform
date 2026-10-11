package com.autocare.platform.order;
import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.service.MerchantActor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;
@RestController
public class CommissionController {
    final ObjectProvider<CommissionLedger> services;
    public CommissionController(ObjectProvider<CommissionLedger> services){this.services=services;}
    static int number(MultiValueMap<String,String> params,String name,int fallback){var values=params.get(name);if(values==null)return fallback;if(values.size()!=1||values.get(0)==null||!values.get(0).matches("[1-9][0-9]{0,6}"))throw ReservationInput.bad();try{return Integer.parseInt(values.get(0));}catch(NumberFormatException e){throw ReservationInput.bad();}}
    @GetMapping("/api/merchant/commissions") Object list(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> params){var actor=MerchantActor.from(jwt);CommissionLedger.manager(actor);if(!Set.of("page","page_size").containsAll(params.keySet()))throw ReservationInput.bad();int page=number(params,"page",1),size=number(params,"page_size",20);if(page>1000000||size>50)throw ReservationInput.bad();var service=services.getIfAvailable();if(service==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"佣金账单数据库尚未配置");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.list(actor,page,size)));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) Object database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"佣金账单暂不可用，请稍后重试"));}
    @ExceptionHandler(ResponseStatusException.class) Object failure(ResponseStatusException error){int status=error.getStatusCode().value();return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(status==400?40001:status*100,error.getReason()));}
}
