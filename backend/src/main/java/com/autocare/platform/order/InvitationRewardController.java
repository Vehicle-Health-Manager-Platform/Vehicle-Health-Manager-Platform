package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.vehicle.VehicleOwner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController public class InvitationRewardController {
    private final ObjectProvider<InvitationRewards> services;
    public InvitationRewardController(ObjectProvider<InvitationRewards> services){this.services=services;}
    InvitationRewards service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"奖励数据库尚未配置");return s;}
    @GetMapping("/api/invite/rewards") public ResponseEntity<?> owner(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=VehicleOwner.from(jwt);int page=InvitationController.query(q,true);return InvitationController.ok(ApiResponse.success(service().records(a,page)));}
    @GetMapping("/api/merchant/invite/rewards") public ResponseEntity<?> staff(@AuthenticationPrincipal Jwt jwt,@RequestParam MultiValueMap<String,String> q){var a=MerchantActor.from(jwt);int page=InvitationController.query(q,true);return InvitationController.ok(ApiResponse.success(service().records(a,page)));}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> error(ResponseStatusException e){int s=e.getStatusCode().value();return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).body(ApiResponse.error(s==400?40001:s*100,e.getReason()));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> database(){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiResponse.error(50300,"奖励服务暂不可用，请重试"));}
}
