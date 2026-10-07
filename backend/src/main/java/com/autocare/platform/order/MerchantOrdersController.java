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
import java.time.*;
import java.util.*;

@RestController
public class MerchantOrdersController {
    private static final Set<String> PARAMETERS=Set.of("status","date","page","page_size");
    private final ObjectProvider<MerchantOrders> orders;
    public MerchantOrdersController(ObjectProvider<MerchantOrders> orders){this.orders=orders;}
    private MerchantOrders configured(){var service=orders.getIfAvailable();if(service==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"本店订单数据库尚未配置");return service;}
    private static ResponseEntity<?> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(value));}
    private static ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"本店订单筛选参数无效");}
    private static String single(MultiValueMap<String,String> parameters,String key){var values=parameters.get(key);if(values==null)return null;if(values.size()!=1||values.get(0)==null||values.get(0).isBlank())throw invalid();return values.get(0);}
    private static int positive(String text,int fallback){if(text==null)return fallback;if(!text.matches("[1-9][0-9]{0,8}"))throw invalid();try{return Integer.parseInt(text);}catch(NumberFormatException error){throw invalid();}}
    private static String status(String text){if(text!=null&&!Set.of("PENDING_PAYMENT","PAID","CLOSED").contains(text))throw invalid();return text;}
    private static String date(String text){if(text==null)return null;if(!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw invalid();try{LocalDate.parse(text);}catch(DateTimeException error){throw invalid();}return text;}
    @GetMapping("/api/merchant/orders") public ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> parameters){
        MerchantActor actor=MerchantActor.from(jwt);
        if(!PARAMETERS.containsAll(parameters.keySet()))throw invalid();
        int page=positive(single(parameters,"page"),1),size=positive(single(parameters,"page_size"),20);
        if(page>1000000||size>100)throw invalid();
        return ok(configured().list(actor,status(single(parameters,"status")),date(single(parameters,"date")),page,size));
    }
    @GetMapping("/api/merchant/orders/{id}") public ResponseEntity<?> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable long id){return ok(configured().detail(MerchantActor.from(jwt),id));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"本店订单暂不可用，请稍后重试"));}
    @ExceptionHandler(ResponseStatusException.class)ResponseEntity<?> failure(ResponseStatusException error){int status=error.getStatusCode().value();return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.error(status==400?40001:status*100,error.getReason()));}
}
