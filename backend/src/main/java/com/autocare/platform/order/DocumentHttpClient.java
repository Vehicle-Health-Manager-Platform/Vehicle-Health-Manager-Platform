package com.autocare.platform.order;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
public final class DocumentHttpClient implements DocumentProvider {
    private final URI endpoint;private final String token;private final Duration deadline;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public DocumentHttpClient(String url,String token,boolean privateHttp){this(url,token,privateHttp,Duration.ofSeconds(75));}
    DocumentHttpClient(String url,String token,boolean privateHttp,Duration deadline){
        try{
            endpoint=URI.create(url);
            if(deadline==null||deadline.toMillis()<1||deadline.toMillis()>75000||endpoint.getHost()==null||endpoint.getUserInfo()!=null||endpoint.getRawQuery()!=null||endpoint.getFragment()!=null||!"/extract".equals(endpoint.getRawPath())
              ||!("https".equals(endpoint.getScheme())||privateHttp&&"http".equals(endpoint.getScheme())&&Set.of("document-extractor","localhost","127.0.0.1").contains(endpoint.getHost()))
              ||token==null||token.length()<32||token.length()>512||!token.chars().allMatch(c->c>=33&&c<=126))throw new IllegalArgumentException();
            this.token=token;this.deadline=deadline;
        }catch(RuntimeException error){throw new IllegalArgumentException("Invalid private document configuration");}
    }
    public JsonNode extract(byte[] bytes,String contentType,int pages){
        if(contentType==null||!Set.of("application/pdf","image/jpeg","image/png").contains(contentType)||bytes==null||bytes.length<1||bytes.length>(contentType.equals("application/pdf")?10485760:4194304))throw new Failed("INVALID_DOCUMENT");
        try{
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            var request=HttpRequest.newBuilder(endpoint).timeout(deadline).header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("version",1,"content_type",contentType,"document_base64",Base64.getEncoder().encodeToString(bytes))))).build();
            var pending=client.sendAsync(request,info->new MileageOcrClient.BoundedBody());HttpResponse<byte[]> response;
            try{response=pending.get(deadline.toMillis(),TimeUnit.MILLISECONDS);}finally{if(!pending.isDone())pending.cancel(true);}
            var types=response.headers().allValues("Content-Type");var encodings=response.headers().allValues("Content-Encoding");
            if(types.size()!=1||!types.get(0).split(";",2)[0].strip().equalsIgnoreCase("application/json")||encodings.size()>1||!encodings.isEmpty()&&!encodings.get(0).strip().equalsIgnoreCase("identity"))throw new Failed("UNAVAILABLE");
            var body=json.readTree(response.body());
            if(response.statusCode()!=200){
                if(Set.of(413,422).contains(response.statusCode())&&body.isObject()&&body.size()==1&&body.path("error").isTextual())throw new Failed(body.path("error").asText());
                throw new Failed("UNAVAILABLE");
            }
            return DocumentResult.validate(body,contentType,hash,pages);
        }catch(InterruptedException error){Thread.currentThread().interrupt();throw new Failed("UNAVAILABLE");}
        catch(Failed error){throw error;}catch(Exception error){throw new Failed("UNAVAILABLE");}
    }
}
