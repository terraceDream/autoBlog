package com.autoblog;

import com.autoblog.collector.SafeHttp;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:testdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH}","spring.datasource.username=${TEST_DB_USER:sa}","spring.datasource.password=${TEST_DB_PASSWORD:}","app.youtube-key=","app.naver-client-id=","app.naver-client-secret="})
class CollectionIntegrationTest {
    @LocalServerPort int port;
    @Autowired Store store;
    @Autowired ObjectMapper json;
    @Autowired CollectionService service;
    @MockitoBean SafeHttp http;
    final HttpClient client=HttpClient.newHttpClient();
    static final String RSS="""
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><title>Tech</title><link>https://example.com/</link><description>News</description>
        <item><title>AI 도구 업데이트</title><link>https://example.com/a?utm_source=rss</link><guid>1</guid><description><![CDATA[<b>새 기능</b> 설명<script>alert(1)</script>]]></description><pubDate>Tue, 08 Sep 2026 09:00:00 +0900</pubDate></item>
        <item><title>AI 도구 업데이트</title><link>https://example.com/a</link><guid>2</guid></item>
        <item><title>AI 광고</title><link>https://example.com/ad</link><guid>3</guid></item>
        <item><title>요리</title><link>https://example.com/cooking</link><guid>4</guid></item>
        </channel></rss>
        """;
    @BeforeEach void setup() throws Exception {
        store.jdbc().update("DELETE FROM topics");store.jdbc().update("DELETE FROM articles");reset(http);
        when(http.get(anyString(),anyMap())).thenReturn(RSS.getBytes(StandardCharsets.UTF_8));
    }
    HttpResponse<String> request(String method,String path,Object body) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path));
        b.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(String method,String path,Object body) throws Exception {var r=request(method,path,body);assertThat(r.statusCode()).withFailMessage(r.body()).isBetween(200,299);return json.readTree(r.body());}
    Map<String,Object> topicInput() {Map<String,Object> m=new LinkedHashMap<>();m.put("name","AI 개발");m.put("description","기술 소식");m.put("instructions","출시 중심");m.put("keywords",List.of("AI"));m.put("exclusions",List.of("광고"));m.put("tags",List.of("기술"));m.put("language","ko");m.put("region","KR");m.put("active",true);m.put("scheduleEnabled",false);m.put("cron","0 0 9 * * *");m.put("timezone","Asia/Seoul");return m;}
    String topic() throws Exception {return ok("POST","/topics",topicInput()).path("id").asText();}
    Map<String,Object> sourceInput(String type) {return Map.of("name","테스트 출처","type",type,"media","NEWS","url","https://93.184.216.34/feed.xml","query","","channelId","","enabled",true);}
    void source(String id) throws Exception {ok("POST","/topics/"+id+"/sources",sourceInput("RSS"));}
    JsonNode finish(String id) throws Exception {
        for(int i=0;i<100;i++) {if(!service.running(id)) return ok("GET","/runs?topicId="+id,null).path("items").get(0);Thread.sleep(40);}
        throw new AssertionError("Collection did not finish");
    }
    @Test void completeFlowDeduplicatesFiltersAndSharesAcrossTopics() throws Exception {
        String id=topic();source(id);ok("POST","/topics/"+id+"/collect",null);JsonNode run=finish(id);
        assertThat(run.path("status").asText()).isEqualTo("SUCCESS");
        JsonNode result=run.path("sources").get(0);assertThat(result.path("added").asInt()).isEqualTo(1);assertThat(result.path("duplicates").asInt()).isEqualTo(1);assertThat(result.path("filtered").asInt()).isEqualTo(2);
        JsonNode page=ok("GET","/articles?topicId="+id,null);assertThat(page.path("total").asInt()).isEqualTo(1);
        JsonNode article=page.path("items").get(0);assertThat(article.path("excerpt").asText()).isEqualTo("새 기능 설명");assertThat(article.path("matchedKeywords").get(0).asText()).isEqualTo("AI");
        String articleId=article.path("id").asText();
        ok("PATCH","/articles/status",Map.of("topicId",id,"ids",List.of(articleId),"status","SAVED"));
        ok("POST","/topics/"+id+"/collect",null);assertThat(finish(id).path("sources").get(0).path("added").asInt()).isZero();
        String second=topic();source(second);ok("POST","/topics/"+second+"/collect",null);finish(second);
        assertThat(store.jdbc().queryForObject("SELECT COUNT(*) FROM articles",Integer.class)).isEqualTo(1);
        assertThat(ok("GET","/articles?topicId="+id+"&status=SAVED",null).path("total").asInt()).isEqualTo(1);
        assertThat(ok("GET","/articles?topicId="+second+"&status=UNREAD",null).path("total").asInt()).isEqualTo(1);
        assertThat(ok("GET","/articles?topicId="+id+"&q=missing",null).path("total").asInt()).isZero();
        assertThat(ok("GET","/articles?topicId="+id+"&from=2026-09-08&to=2026-09-08",null).path("total").asInt()).isEqualTo(1);
        ok("PATCH","/articles/status",Map.of("topicId",id,"ids",List.of(articleId),"status","HIDDEN"));
        assertThat(ok("GET","/articles?topicId="+id,null).path("total").asInt()).isZero();
        assertThat(ok("GET","/articles?topicId="+id+"&status=HIDDEN",null).path("total").asInt()).isEqualTo(1);
    }
    @Test void missingCredentialsDoNotDiscardOtherSources() throws Exception {
        String id=topic();source(id);ok("POST","/topics/"+id+"/sources",sourceInput("YOUTUBE"));ok("POST","/topics/"+id+"/collect",null);
        JsonNode run=finish(id);assertThat(run.path("status").asText()).isEqualTo("PARTIAL");assertThat(run.path("sources").toString()).contains("YOUTUBE_API_KEY");
        assertThat(ok("GET","/articles?topicId="+id,null).path("total").asInt()).isEqualTo(1);
    }
    @Test void duplicateJobsAndDeletesAreRejectedWhileRunning() throws Exception {
        String id=topic();source(id);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(http.get(anyString(),anyMap())).thenAnswer(inv->{entered.countDown();release.await(10,TimeUnit.SECONDS);return RSS.getBytes(StandardCharsets.UTF_8);});
        try {ok("POST","/topics/"+id+"/collect",null);assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();assertThat(request("POST","/topics/"+id+"/collect",null).statusCode()).isEqualTo(409);assertThat(request("DELETE","/topics/"+id,null).statusCode()).isEqualTo(409);}
        finally {release.countDown();finish(id);}
    }
    @Test void scheduledRunAdvancesDeadline() throws Exception {
        String id=topic();source(id);store.jdbc().update("UPDATE topics SET schedule_enabled=TRUE,next_run=? WHERE id=?",Instant.now().minusSeconds(60).toString(),id);
        service.scheduled();JsonNode run=finish(id);assertThat(run.path("triggerType").asText()).isEqualTo("SCHEDULED");assertThat(Instant.parse(store.topic(id).nextRun())).isAfter(Instant.now());
    }
    @Test void validatesInputsAndRollsBackInvalidBulkStatus() throws Exception {
        Map<String,Object> invalid=topicInput();invalid.put("cron","* * * * * *");assertThat(request("POST","/topics",invalid).statusCode()).isEqualTo(400);
        String id=topic();Map<String,Object> local=new HashMap<>(sourceInput("RSS"));local.put("url","http://127.0.0.1/private");assertThat(request("POST","/topics/"+id+"/sources",local).statusCode()).isEqualTo(400);
        source(id);ok("POST","/topics/"+id+"/collect",null);finish(id);String article=ok("GET","/articles?topicId="+id,null).path("items").get(0).path("id").asText();
        assertThat(request("PATCH","/articles/status",Map.of("topicId",id,"ids",List.of(article,"missing"),"status","SAVED")).statusCode()).isEqualTo(404);
        assertThat(ok("GET","/articles?topicId="+id+"&status=UNREAD",null).path("total").asInt()).isEqualTo(1);
        assertThat(request("GET","/articles?topicId="+id+"&page=-1",null).statusCode()).isEqualTo(400);
        var cross=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/topics/"+id+"/collect")).header("Origin","https://evil.example").POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(client.send(cross,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }
}
