package com.autoblog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class TistoryBrowserBridgeTest {
 @TempDir Path directory;
 @Test void connectsOnceAndServesMultipleJobsWithoutReclaiming()throws Exception{
  var bridge=new TistoryBrowserBridge(new ObjectMapper(),directory.toString(),8080);
  assertThatThrownBy(bridge::ensureConnected).isInstanceOf(RuntimeException.class);
  assertThatThrownBy(()->bridge.authorize("wrong")).isInstanceOf(RuntimeException.class);
  bridge.authorize(Files.readString(directory.resolve(".connection-token")));
  assertThat(bridge.claim()).isEmpty();bridge.ensureConnected();
  var executor=Executors.newSingleThreadExecutor();
  try{for(int i=0;i<2;i++){
   var future=executor.submit(()->bridge.submit(Map.of("blogUrl","https://test.tistory.com","title","글")));
   Map<String,Object> job=Map.of();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
   while(job.isEmpty()&&System.nanoTime()<until){job=bridge.claim();if(job.isEmpty())Thread.sleep(10);}
   assertThat(job).containsKey("id");assertThat(bridge.claim()).isEmpty();
   String id=job.get("id").toString();
   assertThatThrownBy(()->bridge.complete("wrong",new TistoryPublisher.Result("SAVED_PRIVATE","확인","https://test.tistory.com/1"))).isInstanceOf(RuntimeException.class);
   assertThatThrownBy(()->bridge.complete(id,new TistoryPublisher.Result("SAVED_PRIVATE","확인","https://other.tistory.com/1"))).isInstanceOf(RuntimeException.class);
   bridge.complete(id,new TistoryPublisher.Result("SAVED_PRIVATE","확인","https://test.tistory.com/1"));
   assertThat(future.get(2,TimeUnit.SECONDS).status()).isEqualTo("SAVED_PRIVATE");
  }}finally{executor.shutdownNow();}
 }
 @Test void extensionHasNoCookieOrDebuggerPermissionAndTokenPersists()throws Exception{
  var json=new ObjectMapper();new TistoryBrowserBridge(json,directory.toString(),8080);
  var token=Files.readString(directory.resolve(".connection-token"));
  new TistoryBrowserBridge(json,directory.toString(),8080);
  assertThat(Files.readString(directory.resolve(".connection-token"))).isEqualTo(token);
  var manifest=json.readTree(Files.readString(directory.resolve("manifest.json")));
  assertThat(manifest.path("permissions").toString()).doesNotContain("cookies","debugger");
 }
}
