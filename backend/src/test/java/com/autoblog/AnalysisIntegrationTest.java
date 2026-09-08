package com.autoblog;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:analysistest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"})
class AnalysisIntegrationTest {
    @Autowired Store store;
    @Autowired ObjectMapper json;
    @Autowired AnalysisService service;
    @LocalServerPort int port;
    @MockitoBean AnalysisRunner runner;
    final HttpClient client=HttpClient.newHttpClient();
    String topic;
    List<String> ids;
    @BeforeEach void setup() throws Exception {
        store.jdbc().update("DELETE FROM topics");store.jdbc().update("DELETE FROM articles");reset(runner);
        when(runner.availability()).thenReturn(new AnalysisRunner.Availability(true,"ChatGPT"));
        topic=UUID.randomUUID().toString();String now=Instant.now().toString();
        store.jdbc().update("INSERT INTO topics(id,name,description,instructions,keywords,exclusions,tags,language,region,active,schedule_enabled,cron,timezone,created_at,updated_at) VALUES(?,?,?,'','[]','[]','[]','ko','KR',TRUE,FALSE,'0 0 9 * * *','Asia/Seoul',?,?)",topic,"개발","실무 기술 중심",now,now);
        ids=new ArrayList<>();for(int i=0;i<3;i++) {
            String id=UUID.randomUUID().toString();ids.add(id);
            store.jdbc().update("INSERT INTO articles(id,canonical_url,url_hash,title,url,media,source_name,author,external_id,excerpt,coverage,published_at,collected_at) VALUES(?,?,?,?,?,'BLOG','출처','','',?,'EXCERPT',?,?)",id,"https://example.com/"+i,CollectionService.hash(id),"테스트 도구 업데이트 "+i,"https://example.com/"+i,"테스트 내용 ".repeat(200),now,now);
            store.jdbc().update("INSERT INTO topic_articles(topic_id,article_id,matched_keywords,status) VALUES(?,?,'[]','UNREAD')",topic,id);
        }
    }
    Map<String,Object> request(List<String> selected){return Map.of("topicId",topic,"articleIds",selected,"mode","QUICK","direction","실무자를 위한 글 구성");}
    HttpResponse<String> call(String method,String path,Object data) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).header("Content-Type","application/json");
        b.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data)));
        return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(String method,String path,Object data) throws Exception {var response=call(method,path,data);assertThat(response.statusCode()).withFailMessage(response.body()).isBetween(200,299);return response.body().isBlank()?json.createObjectNode():json.readTree(response.body());}
    String start(List<String> selected) throws Exception {var req=request(selected);var p=ok("POST","/analysis/preview",req);return ok("POST","/analysis/jobs",Map.of("request",req,"fingerprint",p.path("fingerprint").asText())).path("jobId").asText();}
    JsonNode finish(String id) throws Exception {for(int i=0;i<150;i++){var j=ok("GET","/analysis/jobs/"+id,null);if(!Set.of("QUEUED","RUNNING").contains(j.path("status").asText())){Thread.sleep(50);return j;}Thread.sleep(40);}throw new AssertionError("timeout");}
    String result(List<String> selected) throws Exception {
        return json.writeValueAsString(Map.of("issues",List.of(Map.ofEntries(Map.entry("title","테스트 도구 업데이트"),Map.entry("summary","실무 변경 사항입니다."),Map.entry("category","개발 도구"),Map.entry("categoryReason","도구 변경 소식이기 때문입니다."),Map.entry("angle","기존 방식과 비교합니다."),Map.entry("audience","개발자"),Map.entry("suggestedTitles",List.of("테스트 도구 변경점")),Map.entry("outline",List.of("변경 사항","적용 방법","확인 사항")),Map.entry("keyPoints",List.of("업데이트 소식")),Map.entry("tags",List.of("개발")),Map.entry("sourceIds",selected),Map.entry("uncertainties",List.of("발췌문만 확보했습니다.")))),"excluded",List.of()));
    }
    @Test void previewCostsNoInferenceAndRunsOnlySelectedSourcesWithCache() throws Exception {
        List<String> selected=ids.subList(0,2);var req=request(selected);JsonNode p=ok("POST","/analysis/preview",req);
        assertThat(p.path("input").path("sources")).hasSize(2);assertThat(p.path("truncatedCount").asInt()).isEqualTo(2);
        verify(runner,never()).analyze(anyString(),any(),any());
        when(runner.analyze(anyString(),any(),any())).thenReturn(new AnalysisRunner.Output(result(selected),100L,50L,0L));
        String jobId=start(selected);JsonNode job=finish(jobId);assertThat(job.path("status").asText()).isEqualTo("SUCCESS");assertThat(job.path("result").path("issues")).hasSize(1);assertThat(job.path("inputTokens").asInt()).isEqualTo(100);
        var prompt=org.mockito.ArgumentCaptor.forClass(String.class);verify(runner).analyze(prompt.capture(),any(),any());assertThat(prompt.getValue()).contains(ids.get(0),ids.get(1)).doesNotContain(ids.get(2));
        JsonNode again=ok("POST","/analysis/jobs",Map.of("request",req,"fingerprint",p.path("fingerprint").asText()));assertThat(again.path("cached").asBoolean()).isTrue();assertThat(again.path("jobId").asText()).isEqualTo(jobId);verify(runner,times(1)).analyze(anyString(),any(),any());
    }
    @Test void rejectsForeignSourcesExcessSelectionsAndStalePreview() throws Exception {
        assertThat(call("POST","/analysis/preview",request(List.of("not-in-topic"))).statusCode()).isEqualTo(404);
        assertThat(call("POST","/analysis/preview",request(List.of(ids.get(0),ids.get(0)))).statusCode()).isEqualTo(400);
        assertThat(call("POST","/analysis/preview",request(java.util.stream.IntStream.range(0,21).mapToObj(i->"id"+i).toList())).statusCode()).isEqualTo(400);
        var req=request(ids);var p=ok("POST","/analysis/preview",req);store.jdbc().update("UPDATE topics SET instructions='changed' WHERE id=?",topic);
        assertThat(call("POST","/analysis/jobs",Map.of("request",req,"fingerprint",p.path("fingerprint").asText())).statusCode()).isEqualTo(409);
        verify(runner,never()).analyze(anyString(),any(),any());
    }
    @Test void rejectsInventedCitationsAndMissingSelectedArticles() throws Exception {
        when(runner.analyze(anyString(),any(),any())).thenReturn(new AnalysisRunner.Output(result(List.of("invented-id")),null,null,null));
        assertThat(finish(start(ids)).path("status").asText()).isEqualTo("FAILED");
        when(runner.analyze(anyString(),any(),any())).thenReturn(new AnalysisRunner.Output(result(ids.subList(0,1)),null,null,null));
        assertThat(finish(start(ids)).path("status").asText()).isEqualTo("FAILED");
    }
    @Test void quotaFailureDoesNotRetryOrFallback() throws Exception {
        when(runner.analyze(anyString(),any(),any())).thenThrow(new AnalysisRunner.Failure("LIMIT_REACHED","구독 한도 도달"));
        assertThat(finish(start(ids)).path("status").asText()).isEqualTo("LIMIT_REACHED");verify(runner,times(1)).analyze(anyString(),any(),any());
    }
    @Test void cancellationStopsWorkerAndRejectsDuplicateExecution() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),exited=new CountDownLatch(1);
        when(runner.analyze(anyString(),any(),any())).thenAnswer(inv->{entered.countDown();java.util.function.BooleanSupplier cancelled=inv.getArgument(2);while(!cancelled.getAsBoolean())Thread.sleep(10);exited.countDown();throw new AnalysisRunner.Failure("CANCELLED","중단");});
        String jobId=start(ids);assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
        var req=request(ids);var p=ok("POST","/analysis/preview",req);
        assertThat(call("POST","/analysis/jobs",Map.of("request",req,"fingerprint",p.path("fingerprint").asText())).statusCode()).isEqualTo(409);
        ok("POST","/analysis/jobs/"+jobId+"/cancel",null);assertThat(exited.await(3,TimeUnit.SECONDS)).isTrue();assertThat(finish(jobId).path("status").asText()).isEqualTo("CANCELLED");
    }
}
