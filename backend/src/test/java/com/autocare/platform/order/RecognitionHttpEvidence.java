package com.autocare.platform.order;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual transport observations only; no tokens or source bodies in evidence. */
final class RecognitionHttpEvidence {
 final IsolatedCoreJourneysTest test;final String operation;final List<Map<String,Object>> cases=new ArrayList<>();
 RecognitionHttpEvidence(IsolatedCoreJourneysTest test,String operation){this.test=test;this.operation=operation;}
 static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
 JsonNode send(String name,String kind,String role,String path,String token,Object body,int expected)throws Exception{
  return send(name,kind,role,"POST",operation,path,token,null,body,expected);
 }
 JsonNode send(String name,String kind,String role,String method,String op,String path,String token,String key,Object body,int expected)throws Exception{
  byte[] raw=body==null?new byte[0]:test.mapper.writeValueAsBytes(body);
  var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+test.port+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
  if(token!=null)builder.header("Authorization","Bearer "+token);
  if(key!=null)builder.header("Idempotency-Key",key);
  var response=test.http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(raw)).build(),HttpResponse.BodyHandlers.ofByteArray());
  assertEquals(expected,response.statusCode(),name);assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
  var json=test.mapper.readTree(response.body());if(expected==200)assertEquals(0,json.path("code").asInt(-1));
  cases.add(Map.of("name",name,"kind",kind,"role",role,"method",method,"operation",op,"path",path,"status",response.statusCode(),"passed",true,"request_sha256",hash(raw),"response_sha256",hash(response.body())));
  return json;
 }
 static String sourceDigest()throws Exception{
  Path root=Path.of("..").toAbsolutePath().normalize();var files=new ArrayList<Path>();
  for(String dir:List.of("backend/src","docs/sql/migrations"))try(var stream=Files.walk(root.resolve(dir))){stream.filter(Files::isRegularFile).filter(p->p.toString().endsWith(dir.equals("backend/src")?".java":".sql")).forEach(files::add);}
  files.add(root.resolve("backend/pom.xml"));files.add(root.resolve("scripts/run_recognition_http_acceptance.py"));files.sort(Comparator.comparing(p->root.relativize(p).toString().replace('\\','/')));
  var digest=MessageDigest.getInstance("SHA-256");for(var file:files){digest.update(root.relativize(file).toString().replace('\\','/').getBytes(StandardCharsets.UTF_8));digest.update(Files.readAllBytes(file));}return HexFormat.of().formatHex(digest.digest());
 }
 void finish(String filename,Map<String,Boolean> checks)throws Exception{
  finish(filename,checks,Map.of());
 }
 void finish(String filename,Map<String,Boolean> checks,Map<String,Long> bindings)throws Exception{
  assertTrue(checks.values().stream().allMatch(Boolean.TRUE::equals));
  test.mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target",filename).toFile(),Map.of("version",1,"passed",true,"source_sha256",sourceDigest(),"cases",cases,"checks",checks,"provider_fixture",true,"mysql_container_id",test.MYSQL.getContainerId(),"bindings",bindings));
 }
}
