package com.autocare.platform.order;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** This protocol is exclusively a local fixture, never a WeChat verifier. */
public class LocalTestPaymentChannel implements PaymentChannel {
    private final byte[] secret;private final Clock clock;
    private final java.net.URI queryBase;
    private final java.net.http.HttpClient http=java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
    private final ObjectMapper mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public LocalTestPaymentChannel(String key,Clock clock){this(key,clock,"");}
    public LocalTestPaymentChannel(String key,Clock clock,String url){
        secret=key.getBytes(StandardCharsets.UTF_8);if(secret.length<32)throw new IllegalArgumentException("测试支付密钥至少32字节");this.clock=clock;
        queryBase=url.isBlank()?null:java.net.URI.create(url);
        if(queryBase!=null&&(!"http".equals(queryBase.getScheme())||!Set.of("127.0.0.1","localhost").contains(queryBase.getHost())||queryBase.getUserInfo()!=null||queryBase.getQuery()!=null||queryBase.getFragment()!=null||!Set.of("","/").contains(queryBase.getPath())))throw new IllegalArgumentException("测试支付查询只允许本机HTTP源地址");
    }
    public boolean canQuery(){return queryBase!=null;}
    public Optional<PaymentNotice> query(PaymentQuery request){
        if(!canQuery())throw new UnsupportedOperationException("测试渠道未配置查询");
        if(request.paymentNo()==null||!request.paymentNo().matches("P[a-f0-9]{32}"))throw ReservationInput.bad();
        var target=queryBase.resolve("/payments/"+request.paymentNo());
        try{
            var task=http.sendAsync(java.net.http.HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(3)).GET().build(),info->boundedBody());
            java.net.http.HttpResponse<byte[]> response;
            try{response=task.get(3,java.util.concurrent.TimeUnit.SECONDS);}catch(java.util.concurrent.TimeoutException error){task.cancel(true);throw new IllegalStateException("查询超时");}
                if(response.statusCode()==202||response.statusCode()==404)return Optional.empty();
                if(response.statusCode()!=200)throw new IllegalStateException("查询未成功");
                byte[] raw=response.body();var headers=response.headers();
                var result=verify(raw,headers.firstValue("X-Test-Timestamp").orElse(null),headers.firstValue("X-Test-Nonce").orElse(null),headers.firstValue("X-Test-Signature").orElse(null));
                if(result.paymentId()!=request.paymentId()||!result.orderNo().equals(request.orderNo())||!result.currency().equals(request.currency())||result.amount().compareTo(request.amount())!=0)throw ReservationInput.bad();
                return Optional.of(result);
        }catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException("查询中断");}catch(java.util.concurrent.ExecutionException error){throw new IllegalStateException("查询暂不可用");}
    }
    static java.net.http.HttpResponse.BodySubscriber<byte[]> boundedBody(){
        return new java.net.http.HttpResponse.BodySubscriber<>(){
            final java.net.http.HttpResponse.BodySubscriber<byte[]> delegate=java.net.http.HttpResponse.BodySubscribers.ofByteArray();
            java.util.concurrent.Flow.Subscription subscription;int count;
            public java.util.concurrent.CompletionStage<byte[]> getBody(){return delegate.getBody();}
            public void onSubscribe(java.util.concurrent.Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
            public void onNext(List<java.nio.ByteBuffer> buffers){for(var b:buffers){count+=b.remaining();if(count>16384){subscription.cancel();delegate.onError(new IllegalStateException("查询响应过大"));return;}}delegate.onNext(buffers);}
            public void onError(Throwable error){delegate.onError(error);}
            public void onComplete(){delegate.onComplete();}
        };
    }
    public String name(){return "LOCAL_TEST";}
    public Map<String,Object> prepare(String no,Instant expires){return Map.of("payment_no",no,"test_mode",true,"expires_at",expires.toString());}
    public PaymentNotice verify(byte[] raw,String timestamp,String nonce,String signature){
        if(raw==null||raw.length>16384||timestamp==null||!timestamp.matches("[0-9]{1,12}")||nonce==null||!nonce.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")||signature==null||!signature.matches("[a-fA-F0-9]{64}"))throw ReservationInput.bad();
        long seconds=Long.parseLong(timestamp);if(Math.abs(clock.instant().getEpochSecond()-seconds)>300)throw denied();
        try{
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            mac.update((timestamp+"\n"+nonce+"\n").getBytes(StandardCharsets.UTF_8));mac.update(raw);
            if(!MessageDigest.isEqual(mac.doFinal(new byte[]{10}),HexFormat.of().parseHex(signature)))throw denied();
            JsonNode n=mapper.readTree(raw);ReservationInput.fields(n,"event_id","payment_id","channel_payment_no","status","amount","currency","order_no","occurred_at");
            String event=string(n,"event_id"),transaction=string(n,"channel_payment_no"),status=string(n,"status"),amount=string(n,"amount"),currency=string(n,"currency"),order=string(n,"order_no");
            if(!event.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")||!transaction.matches("[a-zA-Z0-9_-]{1,128}")||!Set.of("SUCCEEDED","FAILED").contains(status)||!amount.matches("(?:0|[1-9][0-9]{0,7})\\.[0-9]{2}")||new BigDecimal(amount).signum()<=0||!"CNY".equals(currency)||!order.matches("[a-zA-Z0-9_-]{1,64}"))throw ReservationInput.bad();
            Instant occurred=OffsetDateTime.parse(string(n,"occurred_at")).toInstant();if(occurred.isAfter(clock.instant().plusSeconds(300)))throw ReservationInput.bad();
            var canonical=new TreeMap<String,Object>();canonical.put("event_id",UUID.fromString(event).toString());canonical.put("payment_id",ReservationInput.id(n.get("payment_id")));canonical.put("channel_payment_no",transaction);canonical.put("status",status);canonical.put("amount",amount);canonical.put("currency",currency);canonical.put("order_no",order);canonical.put("occurred_at",occurred.toString());
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical)));
            return new PaymentNotice(UUID.fromString(event).toString(),ReservationInput.id(n.get("payment_id")),transaction,status,new BigDecimal(amount),currency,order,occurred,hash);
        }catch(ResponseStatusException error){throw error;}catch(Exception error){throw ReservationInput.bad();}
    }
    private static String string(JsonNode n,String key){if(!n.path(key).isTextual())throw ReservationInput.bad();return n.path(key).asText();}
    private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"测试支付通知签名无效或已过期");}
}
