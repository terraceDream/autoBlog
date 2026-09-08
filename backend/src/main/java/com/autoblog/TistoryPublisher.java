package com.autoblog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Component
public class TistoryPublisher {
 public record Result(String status,String message,String url){}
 private final ObjectMapper json;public TistoryPublisher(ObjectMapper j){json=j;}
 public Result send(Map<String,Object> payload)throws Exception{
  Path script=Path.of("../analysis-runtime/tistory.mjs").toAbsolutePath().normalize();
  var builder=new ProcessBuilder("node",script.toString()).directory(script.getParent().toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
  var p=builder.start();var executor=Executors.newSingleThreadExecutor();
  try{
   try(var input=p.getOutputStream()){input.write(json.writeValueAsBytes(payload));}
   var line=executor.submit(()->new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8)).readLine()).get(600,TimeUnit.SECONDS);
   var result=json.readTree(line);String status=result.path("status").asText();
   if(!Set.of("SAVED_PRIVATE","EDITOR_READY","UNKNOWN").contains(status))throw new IllegalArgumentException();
   return new Result(status,result.path("message").asText(),result.path("url").asText());
  }catch(Exception e){CodexAnalysisRunner.kill(p);throw e;}finally{executor.shutdownNow();}
 }
}
