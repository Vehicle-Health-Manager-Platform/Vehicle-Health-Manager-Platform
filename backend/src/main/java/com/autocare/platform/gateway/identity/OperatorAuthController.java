package com.autocare.platform.gateway.identity;

import com.autocare.platform.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/auth/operator")
public class OperatorAuthController {
    private final ObjectProvider<OperatorIdentity> identities;private final ObjectProvider<OperatorSmsSender> senders;private final ObjectProvider<AuthRateLimiter> limiters;
    public OperatorAuthController(ObjectProvider<OperatorIdentity> identities,ObjectProvider<OperatorSmsSender> senders,ObjectProvider<AuthRateLimiter> limiters){this.identities=identities;this.senders=senders;this.limiters=limiters;}
    private OperatorIdentity service(){var s=identities.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"运营身份数据库尚未配置");return s;}
    private static ResponseEntity<?> ok(Object d){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(d));}
    private void rate(String account,HttpServletRequest r,boolean code){var l=limiters.getIfAvailable();if(l==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"限流数据库尚未配置");l.check("operator-ip",r.getRemoteAddr(),30,900);l.check("operator-account",account,10,900);if(code){l.check("operator-sms-minute",account,1,60);l.check("operator-sms-day",account,5,86400);}}
    private ResponseEntity<?> auth(String raw,MultiValueMap<String,String> p,HttpServletRequest r,boolean code){if(!p.isEmpty())throw OperatorInput.bad();var b=OperatorInput.parse(raw);OperatorInput.login(b,code);String account=OperatorInput.account(b.get("account").textValue());rate(account,r,code);if(code){service().code(account,b.get("password").textValue(),senders.getIfAvailable());return ok(java.util.Map.of("sent",true,"expires_in",300));}return ok(service().login(account,b.get("password").textValue(),b.get("sms_code").textValue()));}
    @PostMapping(value="/code",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> code(@RequestBody String raw,@RequestParam MultiValueMap<String,String> p,HttpServletRequest r){return auth(raw,p,r,true);}
    @PostMapping(value="/login",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> login(@RequestBody String raw,@RequestParam MultiValueMap<String,String> p,HttpServletRequest r){return auth(raw,p,r,false);}
    @PostMapping(value="/logout",consumes=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> logout(@AuthenticationPrincipal Jwt jwt,@RequestBody String raw,@RequestParam MultiValueMap<String,String> p){var a=OperatorActor.from(jwt);if(!p.isEmpty() || !OperatorInput.parse(raw).isEmpty())throw OperatorInput.bad();service().logout(a);return ok(java.util.Map.of("revoked",true));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> db(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"运营身份服务暂不可用"));}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
}
