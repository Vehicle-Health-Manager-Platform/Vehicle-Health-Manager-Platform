package com.autocare.platform.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import com.autocare.platform.gateway.wechat.WechatExchangeException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({org.springframework.web.HttpMediaTypeNotSupportedException.class,
                       org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> invalidHttpInput() {
        return ResponseEntity.badRequest().body(ApiResponse.error(40001,"请求格式无效"));
    }
    @ExceptionHandler(com.autocare.platform.file.UploadHttpException.class)
    public ResponseEntity<ApiResponse<Void>> uploadHttp(com.autocare.platform.file.UploadHttpException error) {
        var result=ResponseEntity.status(error.status()).header("Cache-Control","no-store");
        if(error.retry()>0) result.header("Retry-After",Integer.toString(error.retry()));
        return result.body(ApiResponse.error(error.status()*100+(error.status()==400?1:0),error.getMessage()));
    }
    @ExceptionHandler(com.autocare.platform.file.UploadException.class)
    public ResponseEntity<ApiResponse<Void>> uploadCore(com.autocare.platform.file.UploadException error) {
        int status=switch(error.reason()) {
            case INVALID_FILE -> 400;
            case INFECTED -> 422;
            case NOT_FOUND -> 404;
            case NOT_READABLE -> 409;
            case UNAVAILABLE -> 503;
        };
        return ResponseEntity.status(status).header("Cache-Control","no-store")
            .body(ApiResponse.error(status*100+(status==400?1:0),error.getMessage()));
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> uploadSize() {
        return ResponseEntity.status(413).body(ApiResponse.error(41300,"上传请求过大"));
    }
    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> malformedMultipart() {
        return ResponseEntity.badRequest().body(ApiResponse.error(40001,"上传请求格式无效"));
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadableBody() {
        return ResponseEntity.badRequest().body(ApiResponse.error(40001, "请求正文格式无效"));
    }
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
