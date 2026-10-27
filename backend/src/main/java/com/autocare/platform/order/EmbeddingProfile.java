package com.autocare.platform.order;
import com.autocare.platform.gateway.identity.AuthTokens;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Immutable processing identity. A profile is configuration, never model accuracy evidence. */
public record EmbeddingProfile(String model,String revision,int dimension,String queryPrefix) {
 public static final String CHUNK_POLICY="UNICODE_256_OVERLAP32_V1";
 public EmbeddingProfile {
  if(model==null||!model.matches("[A-Za-z0-9][A-Za-z0-9_./-]{0,119}")||revision==null||!revision.matches("(?:[a-f0-9]{40}|[a-f0-9]{64})")||dimension<1||dimension>1024||queryPrefix==null||queryPrefix.length()>256||queryPrefix.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.SURROGATE))throw new IllegalArgumentException("Invalid embedding profile");
 }
 public String json(){try{return new ObjectMapper().writeValueAsString(new TreeMap<>(Map.of("model",model,"revision",revision,"dimension",dimension,"query_prefix",queryPrefix,"normalization","L2_V1","chunk_policy",CHUNK_POLICY)));}catch(Exception e){throw new IllegalStateException("Embedding profile unavailable");}}
 public String fingerprint(){return AuthTokens.sha256(json());}
}
