package com.autocare.platform.file;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/** Runs after bearer verification, before MVC/multipart parsing. No file bytes read here. */
public class UploadAdmissionFilter extends OncePerRequestFilter {
    public static final String OWNER=UploadAdmissionFilter.class.getName()+".owner";
    private final ObjectProvider<UploadHttpService> services;
    private final ObjectMapper mapper;
    private final Semaphore slots;
    @org.springframework.beans.factory.annotation.Value("${WECHAT_APP_ID:}") private String appId="";
    public UploadAdmissionFilter(ObjectProvider<UploadHttpService> services,ObjectMapper mapper,int maximum) {
        if(maximum<1) throw new IllegalArgumentException("Upload concurrency must be positive");
        this.services=services;this.mapper=mapper;slots=new Semaphore(maximum);
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getServletPath();
        return !("POST".equals(request.getMethod()) && (path.equals("/api/file/upload") || path.equals("/api/merchant/files/upload") || path.equals("/api/tech/files/upload")))
            && !("GET".equals(request.getMethod()) && (path.matches("/api/file/[^/]+/access") || path.matches("/api/merchant/files/[^/]+/access") || path.matches("/api/tech/files/[^/]+/access")));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
        throws ServletException,IOException {
        boolean held=false;
        try {
            var authentication=SecurityContextHolder.getContext().getAuthentication();
            if(!(authentication instanceof JwtAuthenticationToken token)) throw new UploadHttpException(401,"未登录或登录已失效");
            UploadOwner owner=request.getServletPath().startsWith("/api/merchant/files/")
                ? UploadOwner.merchant(token.getToken()) : request.getServletPath().startsWith("/api/tech/files/") ? UploadOwner.technician(token.getToken(),appId) : UploadOwner.from(token.getToken());
            boolean upload="POST".equals(request.getMethod());
            if(upload) WriteIntegrityService.normalizeKey(request.getHeader("Idempotency-Key"));
            var service=services.getIfAvailable(); if(service==null) throw UploadHttpException.unavailable();
            service.admission(owner,upload);
            response.setHeader("Cache-Control","no-store");
            if(upload) {
                if(request.getContentLengthLong()>11L*1024*1024) throw new UploadHttpException(413,"上传请求过大");
                if(!slots.tryAcquire()) throw new UploadHttpException(429,"上传繁忙，请稍后重试",2);
                held=true;
            }
            request.setAttribute(OWNER,owner);
            chain.doFilter(request,response);
        } catch(UploadHttpException e) {write(response,e.status(),e.getMessage(),e.retry());}
        catch(ResponseStatusException e) {write(response,e.getStatusCode().value(),"上传请求无效",0);}
        finally {if(held) slots.release();}
    }
    private void write(HttpServletResponse response,int status,String message,int retry) throws IOException {
        if(response.isCommitted()) return;
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control","no-store");
        if(retry>0) response.setHeader("Retry-After",Integer.toString(retry));
        mapper.writeValue(response.getWriter(),ApiResponse.error(status*100+(status==400?1:0),message));
    }
}
