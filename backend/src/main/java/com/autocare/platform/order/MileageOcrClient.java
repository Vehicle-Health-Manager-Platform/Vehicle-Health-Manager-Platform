package com.autocare.platform.order;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Server-configured destination only; bounded body and deadline, no retries or redirects. */
public final class MileageOcrClient implements MileageRecognition.Provider {
    private final URI endpoint;
    private final Duration deadline;
    private final String token;
    private final String scene;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public MileageOcrClient(String url,String token,boolean allowPrivateHttp){this(url,token,allowPrivateHttp,Duration.ofSeconds(15));}
    MileageOcrClient(String url,String token,boolean allowPrivateHttp,Duration deadline){
        this(url,token,allowPrivateHttp,deadline,"mileage");
    }
    public static MileageOcrClient forLicense(String url,String token,boolean allowPrivateHttp){
        return forLicense(url,token,allowPrivateHttp,Duration.ofSeconds(10));
    }
    static MileageOcrClient forLicense(String url,String token,boolean allowPrivateHttp,Duration deadline){
        if(deadline==null||deadline.toMillis()<1||deadline.toMillis()>10000)throw new IllegalArgumentException("Invalid license OCR deadline");
        return new MileageOcrClient(url,token,allowPrivateHttp,deadline,"license");
    }
    private MileageOcrClient(String url,String token,boolean allowPrivateHttp,Duration deadline,String scene){
        if(deadline==null||deadline.toMillis()<1||deadline.toMillis()>15000)throw new IllegalArgumentException("Invalid OCR deadline");
        this.deadline=deadline;
        this.scene=scene;
        try{endpoint=URI.create(url);String host=endpoint.getHost();
            if(host==null||endpoint.getUserInfo()!=null||endpoint.getRawQuery()!=null||endpoint.getFragment()!=null||!"/recognize".equals(endpoint.getRawPath())
                ||!("https".equals(endpoint.getScheme())||allowPrivateHttp&&"http".equals(endpoint.getScheme())&&Set.of("ocr","127.0.0.1","localhost").contains(host))
                ||token==null||token.length()<32||token.length()>512||!token.chars().allMatch(c->c>=33&&c<=126))throw new IllegalArgumentException();
            this.token=token;
        }catch(RuntimeException e){throw new IllegalArgumentException("Invalid private OCR configuration");}
    }
    public JsonNode recognize(byte[] bytes){
        if(bytes==null||bytes.length==0||bytes.length>4194304)throw MileageRecognition.unavailable();
        try{
            var request=HttpRequest.newBuilder(endpoint).timeout(deadline).header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("scene",scene,"image_base64",Base64.getEncoder().encodeToString(bytes))))).build();
            var pending=client.sendAsync(request,info->new BoundedBody());
            HttpResponse<byte[]> response;
            try{response=pending.get(deadline.toMillis(),TimeUnit.MILLISECONDS);}finally{if(!pending.isDone())pending.cancel(true);}
            var types=response.headers().allValues("Content-Type");var encodings=response.headers().allValues("Content-Encoding");
            if(response.statusCode()!=200||types.size()!=1||!types.get(0).split(";",2)[0].strip().equalsIgnoreCase("application/json")
                ||encodings.size()>1||!encodings.isEmpty()&&!"identity".equalsIgnoreCase(encodings.get(0).strip()))throw MileageRecognition.unavailable();
            return json.readTree(response.body());
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw MileageRecognition.unavailable();}
        catch(Exception e){throw MileageRecognition.unavailable();}
    }
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result=new CompletableFuture<>();
        final java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
        Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> chunks){
            if(result.isDone())return;
            for(var buffer:chunks){if(buffer.remaining()>65536-output.size()){subscription.cancel();result.completeExceptionally(new IllegalStateException("OCR response too large"));return;}
                byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);output.writeBytes(bytes);}
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(output.toByteArray());}
    }
}
