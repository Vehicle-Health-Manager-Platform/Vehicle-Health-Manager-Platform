package com.autocare.platform.order;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.gateway.identity.OperatorActor;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class LocalFinanceController {
    private final ObjectProvider<LocalFinance> reports;
    public LocalFinanceController(ObjectProvider<LocalFinance> reports) { this.reports = reports; }

    @GetMapping("/api/admin/reconciliation")
    public ResponseEntity<?> report(@AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String,String> query) {
        var actor = OperatorActor.from(jwt);
        if (!query.keySet().equals(Set.of("date", "channel")) || query.values().stream().anyMatch(v -> v.size() != 1))
            throw ReservationInput.bad();
        String date = query.getFirst("date"), channel = query.getFirst("channel");
        LocalFinance.date(date); LocalFinance.channel(channel);
        var service = reports.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "资金记录数据库尚未配置");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.report(actor, date, channel)));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> error(ResponseStatusException e) {
        int status = e.getStatusCode().value();
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body(ApiResponse.error(status == 400 ? 40001 : status * 100, e.getReason()));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
            .body(ApiResponse.error(50300, "资金来源暂不可读取，请稍后重试"));
    }
}
