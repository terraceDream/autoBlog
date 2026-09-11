package com.autoblog;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
class ServerOriginTest {
 @Test void productionWritesRequireExactConfiguredOrigin() throws Exception {
  var filter=new LocalOriginFilter();ReflectionTestUtils.setField(filter,"publicOrigin","https://54.116.23.226");
  for(String origin:new String[]{"https://54.116.23.226","http://54.116.23.226","https://evil.test","http://127.0.0.1:8080"}){
   var req=new MockHttpServletRequest("POST","/api/topics");req.addHeader("Origin",origin);
   var res=new MockHttpServletResponse();var chain=new MockFilterChain();filter.doFilter(req,res,chain);
   assertThat(res.getStatus()).isEqualTo(origin.equals("https://54.116.23.226")?200:403);
  }
 }
 @Test void serverExtensionUsesOnlyConfiguredHostAndContainsNoAccountCredentials() throws Exception {
  var directory=java.nio.file.Files.createTempDirectory("bridge-test");
  var bridge=new TistoryBrowserBridge(new com.fasterxml.jackson.databind.ObjectMapper(),directory.toString(),8080);
  ReflectionTestUtils.setField(bridge,"publicOrigin","https://54.116.23.226");
  var entries=new java.util.HashMap<String,String>();
  try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bridge.extensionBundle()))){
   for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())entries.put(entry.getName(),new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
  }
  assertThat(entries).containsOnlyKeys("manifest.json","worker.js","page-actions.js","new-draft.js","config.js");
  assertThat(entries.get("manifest.json")).contains("https://54.116.23.226/*").doesNotContain("127.0.0.1","cookies","debugger");
  assertThat(entries.get("config.js")).contains("https://54.116.23.226/api/tistory-browser");
  for(var name:java.util.List.of("manifest.json","worker.js","page-actions.js","new-draft.js","config.js",".connection-token"))java.nio.file.Files.deleteIfExists(directory.resolve(name));
  java.nio.file.Files.delete(directory);
 }
}
