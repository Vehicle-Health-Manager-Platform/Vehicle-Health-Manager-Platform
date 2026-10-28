package com.autocare.platform.order;
import com.autocare.platform.gateway.identity.AuthTokens;
import java.util.*;
/** Bounded Unicode windows. Actual model tokenizer budgets must be checked by a future adapter. */
public final class KnowledgeChunks {
 public record Chunk(int ordinal,int start,int end,String text,String hash){}
 public static List<Chunk> split(String body){
  if(body==null||body.isBlank()||body.codePoints().anyMatch(c->Character.getType(c)==Character.SURROGATE||Character.isISOControl(c)&&c!='\n')||body.codePointCount(0,body.length())>10000)throw new IllegalArgumentException("Invalid embedding text");
  var chunks=new ArrayList<Chunk>();int start=0;
  while(start<body.length()){
   int count=Math.min(256,body.codePointCount(start,body.length()));int end=body.offsetByCodePoints(start,count);String text=body.substring(start,end);
   chunks.add(new Chunk(chunks.size(),start,end,text,AuthTokens.sha256(text)));if(chunks.size()>64)throw new IllegalArgumentException("Embedding chunk budget exceeded");
   if(end==body.length())break;start=body.offsetByCodePoints(start,count-32);
  }
  return List.copyOf(chunks);
 }
 private KnowledgeChunks(){}
}
