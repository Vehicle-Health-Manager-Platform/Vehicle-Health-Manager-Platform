package com.autocare.platform.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import com.autocare.platform.gateway.wechat.WechatExchangeException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> status(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        int code = switch (status) {
            case FORBIDDEN -> 40300;
            case NOT_FOUND -> 40400;
            case CONFLICT -> 40900;
            case SERVICE_UNAVAILABLE -> 50300;
            case TOO_MANY_REQUESTS -> 42900;
            case UNAUTHORIZED -> 40100;
            default -> 40001;
        };
        return ResponseEntity.status(status).body(ApiResponse.error(code, exception.getReason()));
    }

    @ExceptionHandler(WechatExchangeException.class)
    public ResponseEntity<ApiResponse<Void>> wechat(WechatExchangeException exception) {
        HttpStatus status = switch (exception.reason()) {
            case INVALID_CODE -> HttpStatus.BAD_REQUEST;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case UNCONFIGURED, UPSTREAM_FAILURE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        int code = status == HttpStatus.TOO_MANY_REQUESTS ? 42900 : status == HttpStatus.BAD_REQUEST ? 40001 : 50300;
        return ResponseEntity.status(status).body(ApiResponse.error(code, exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error(40001, exception.getMessage()));
    }
}
