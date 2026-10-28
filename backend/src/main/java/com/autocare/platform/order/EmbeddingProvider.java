package com.autocare.platform.order;
import java.util.List;
/** Explicit adapter seam; no default, hash-vector or keyword implementation. */
@FunctionalInterface public interface EmbeddingProvider {
 final class Failed extends RuntimeException {
  private final String code;
  public Failed(String code){super(null,null,false,false);this.code=java.util.Set.of("UNAVAILABLE","INVALID_VECTOR","LIMIT_EXCEEDED").contains(code==null?"":code)?code:"UNAVAILABLE";}
  public String code(){return code;}
 }
 record Output(String profileHash,List<double[]> vectors){}
 Output embed(EmbeddingProfile profile,List<String> texts);
}
