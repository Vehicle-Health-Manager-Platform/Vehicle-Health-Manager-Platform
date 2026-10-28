package com.autocare.platform.order;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class AsrHttpClient implements AsrProvider {
    private final URI endpoint;private final String token;private final Duration deadline;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public AsrHttpClient(String url,String token,boolean privateHttp){this(url,token,privateHttp,Duration.ofSeconds(90));}
    AsrHttpClient(String url,String token,boolean privateHttp,Duration deadline){
        try{
            endpoint=URI.create(url);
            if(deadline==null||deadline.toMillis()<1||deadline.toMillis()>90000||endpoint.getHost()==null||endpoint.getUserInfo()!=null||endpoint.getRawQuery()!=null||endpoint.getFragment()!=null||!"/transcribe".equals(endpoint.getRawPath())
                ||!("https".equals(endpoint.getScheme())||privateHttp&&"http".equals(endpoint.getScheme())&&Set.of("asr","127.0.0.1","localhost").contains(endpoint.getHost()))
                ||token==null||token.length()<32||token.length()>512||!token.chars().allMatch(c->c>=33&&c<=126))throw new IllegalArgumentException();
            this.token=token;this.deadline=deadline;
        }catch(RuntimeException e){throw new IllegalArgumentException("Invalid private ASR configuration");}
    }
    public JsonNode transcribe(byte[] audio,int durationMs){
        if(audio==null||audio.length==0||audio.length>3145728||durationMs<1000||durationMs>60000)throw new Unavailable();
        try{
            var request=HttpRequest.newBuilder(endpoint).timeout(deadline).header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("version",1,"task","ASR","audio_base64",Base64.getEncoder().encodeToString(audio),"duration_ms",durationMs)))).build();
            var pending=client.sendAsync(request,info->new MileageOcrClient.BoundedBody());HttpResponse<byte[]> response;
            try{response=pending.get(deadline.toMillis(),TimeUnit.MILLISECONDS);}finally{if(!pending.isDone())pending.cancel(true);}
            var types=response.headers().allValues("Content-Type");var encodings=response.headers().allValues("Content-Encoding");
            if(response.statusCode()!=200||types.size()!=1||!types.get(0).split(";",2)[0].strip().equalsIgnoreCase("application/json")||encodings.size()>1||!encodings.isEmpty()&&!"identity".equalsIgnoreCase(encodings.get(0).strip()))throw new Unavailable();
            return AsrResult.validate(json.readTree(response.body()),durationMs);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new Unavailable();}catch(Exception e){throw new Unavailable();}
    }
}
