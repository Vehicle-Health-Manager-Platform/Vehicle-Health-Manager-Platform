package com.autocare.platform;

import com.autocare.platform.file.*;
import com.autocare.platform.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual embedded Tomcat, not MockMultipartFile: exercises parser and chunked limits. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","UPLOAD_HTTP_CONCURRENCY=1"})
class UploadHttpTransportTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean UploadHttpService service;
    @MockitoBean PrivateFileAccessService access;
    final HttpClient client=HttpClient.newHttpClient();
    final String boundary="autocare-test-boundary";
    @BeforeEach void setup() {
        when(decoder.decode(anyString())).thenAnswer(invocation->{
            String token=invocation.getArgument(0);var builder=Jwt.withTokenValue(token).header("alg","HS256").subject("1001")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600));
            if(!token.equals("local"))builder.claim("subject_type",token.equals("binding")?"wechat_binding":token.equals("staff")?"staff_account":"user");
            return builder.claim("role",token.equals("staff")?"TECHNICIAN":"OWNER").claim("jti",UUID.randomUUID().toString()).build();
        });
        when(service.upload(any(),anyString(),anyString(),any())).thenReturn(mapper.valueToTree(ApiResponse.success(Map.of("file_id",1,"content_type","image/png","size_bytes",11))));
        when(access.sign(any(),anyLong())).thenReturn(new PrivateFileAccessService.SignedAccess("https://test.invalid/signed",Instant.now().plusSeconds(120)));
    }
    byte[] multipart(int length,String extra) throws Exception {
        var output=new java.io.ByteArrayOutputStream();
        output.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        byte[] bytes=new byte[length];System.arraycopy(PrivateUploadTest.PNG,0,bytes,0,Math.min(bytes.length,PrivateUploadTest.PNG.length));output.write(bytes);
        output.write(("\r\n"+extra+"--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));return output.toByteArray();
    }
    HttpResponse<String> post(byte[] bytes,String token,boolean chunked,String key) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/file/upload")).timeout(java.time.Duration.ofSeconds(30))
            .header("Content-Type","multipart/form-data; boundary="+boundary);
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if(key!=null)builder.header("Idempotency-Key",key);
        return client.send(builder.POST(chunked?HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(bytes)):HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofString());
    }
    String key(){return UUID.randomUUID().toString();}
    @Test void actualMultipartAcceptsSingleFileAndExactTenMiBBoundary() throws Exception {
        var response=post(multipart(FileValidator.MAX_BYTES,""),"owner",false,key());assertEquals(200,response.statusCode(),response.body());
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(1,mapper.readTree(response.body()).path("data").path("file_id").asInt());
    }
    @Test void actualParserRejectsOversizeFileForFixedAndChunkedRequests() throws Exception {
        for(boolean chunked:List.of(false,true)) {
            var response=post(multipart(FileValidator.MAX_BYTES+1,""),"owner",chunked,key());
            assertEquals(413,response.statusCode(),response.body());assertEquals(41300,mapper.readTree(response.body()).path("code").asInt());
        }
        verify(service,never()).upload(any(),any(),any(),any());
    }
    @Test void totalRequestLimitRejectsOversizedBodyBeforeUpload() throws Exception {
        var response=post(multipart(11*1024*1024,""),"owner",false,key());assertEquals(413,response.statusCode());
        verify(service,never()).upload(any(),any(),any(),any());
    }
    @Test void unauthenticatedRoleLocalAndBindingAreRejectedBeforeMultipartParsing() throws Exception {
        byte[] invalid="not a multipart body".getBytes(StandardCharsets.US_ASCII);
        assertEquals(401,post(invalid,null,false,key()).statusCode());
        for(String token:List.of("staff","local","binding")) assertEquals(403,post(invalid,token,false,key()).statusCode());
        verify(service,never()).admission(any(),anyBoolean());verify(service,never()).upload(any(),any(),any(),any());
    }
    @Test void missingOrInvalidIdempotencyKeyDoesNotReachAdmission() throws Exception {
        for(String key:Arrays.asList(null,"invalid")) assertEquals(400,post(multipart(11,""),"owner",false,key).statusCode());
        verify(service,never()).admission(any(),anyBoolean());
    }
    @Test void extraFieldsAndDuplicateFilesAreRejected() throws Exception {
        String field="--"+boundary+"\r\nContent-Disposition: form-data; name=\"owner_id\"\r\n\r\n1002\r\n";
        assertEquals(400,post(multipart(11,field),"owner",false,key()).statusCode());
        String file="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"second.png\"\r\n\r\n123\r\n";
        assertEquals(400,post(multipart(11,file),"owner",false,key()).statusCode());verify(service,never()).upload(any(),any(),any(),any());
    }
    @Test void dependencyAndRateErrorsAreSafeAndDoNotParseBody() throws Exception {
        doThrow(UploadHttpException.unavailable()).when(service).admission(any(),anyBoolean());
        assertEquals(503,post(new byte[]{1},"owner",false,key()).statusCode());
        doThrow(new UploadHttpException(429,"图片操作过于频繁",17)).when(service).admission(any(),anyBoolean());
        var response=post(new byte[]{1},"owner",false,key());assertEquals(429,response.statusCode());assertEquals("17",response.headers().firstValue("Retry-After").orElseThrow());
    }
    @Test void admissionConcurrencyRejectsAndReleasesAfterRequestCompletes() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(service.upload(any(),any(),any(),any())).thenAnswer(invocation->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return mapper.valueToTree(ApiResponse.success(Map.of("file_id",1)));});
        var first=CompletableFuture.supplyAsync(()->{try{return post(multipart(11,""),"owner",false,key());}catch(Exception e){throw new CompletionException(e);}});
        try {assertTrue(entered.await(10,TimeUnit.SECONDS));assertEquals(429,post(multipart(11,""),"owner",false,key()).statusCode());}
        finally {release.countDown();}
        assertEquals(200,first.get(10,TimeUnit.SECONDS).statusCode());assertEquals(200,post(multipart(11,""),"owner",false,key()).statusCode());
    }
    @Test void accessIsNotCachedAndFailureDoesNotExposeSignedUrl() throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/file/1/access")).header("Authorization","Bearer owner").GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(mapper.readTree(response.body()).path("data").has("expires_at"));
        when(access.sign(any(),anyLong())).thenThrow(new UploadException(UploadException.Reason.NOT_FOUND));
        response=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(404,response.statusCode());assertFalse(response.body().contains("signed"));
    }
}
