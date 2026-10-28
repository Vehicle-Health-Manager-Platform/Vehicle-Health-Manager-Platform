package com.autocare.platform.order;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
@FunctionalInterface public interface DocumentProvider {
 JsonNode extract(byte[] bytes,String contentType,int pages);
 final class Failed extends RuntimeException {
  private final String code;
  public Failed(String code){super("Document extraction unavailable");this.code=Set.of("INVALID_DOCUMENT","LIMIT_EXCEEDED","INVALID_RESULT").contains(code)?code:"UNAVAILABLE";}
  public String code(){return code;}
 }
}
