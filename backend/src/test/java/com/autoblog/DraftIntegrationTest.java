package com.autoblog;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:drafttest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"})
class DraftIntegrationTest {
 @Autowired Store store;@Autowired DraftService drafts;@Autowired ObjectMapper json;
 @MockitoBean AnalysisRunner runner;@MockitoBean OriginalReader originals;@MockitoBean TistoryPublisher publisher;
 String analysis;
 @BeforeEach void setup()throws Exception{
  reset(runner,originals,publisher);store.jdbc().update("DELETE FROM topics");String topic=UUID.randomUUID().toString(),now=Instant.now().toString();analysis=UUID.randomUUID().toString();
  store.jdbc().update("INSERT INTO topics(id,name,description,instructions,keywords,exclusions,tags,language,region,active,schedule_enabled,cron,timezone,created_at,updated_at) VALUES(?,'개발','','','[]','[]','[]','ko','KR',TRUE,FALSE,'0 0 9 * * *','Asia/Seoul',?,?)",topic,now,now);
  String input="{\"sources\":[{\"id\":\"a\",\"title\":\"원문\",\"url\":\"https://example.com/a\",\"excerpt\":\"근거\"},{\"id\":\"b\",\"title\":\"다른 소재\",\"url\":\"https://example.com/b\"}]}";
  store.jdbc().update("INSERT INTO analysis_jobs(id,topic_id,request_hash,mode,direction,status,article_count,input_chars,input_json,result_json,message,created_at) VALUES(?,?,'hash','QUICK','','SUCCESS',2,100,?,?,'',?)",analysis,topic,input,"{\"issues\":[{\"title\":\"소재\",\"sourceIds\":[\"a\"]}]}",now);
  when(originals.read(any())).thenReturn(Map.of("id","a","title","원문","url","https://example.com/a","text","검증한 원문 내용","coverage","ORIGINAL_EXTRACT","message","추출 완료"));
  when(runner.analyze(anyString(),any(),any())).thenReturn(new AnalysisRunner.Output("{\"title\":\"블로그 제목\",\"html\":\"<p>설명</p><script>alert(1)</script><img src=x>\",\"category\":\"개발\",\"tags\":[\"AI\"],\"checks\":[\"검수하세요\"]}",200L,100L,0L));
 }
 Map<String,Object> finish(String id)throws Exception{for(int i=0;i<200;i++){var d=drafts.get(id);if(!Set.of("WRITING","SENDING").contains(d.get("status"))){Thread.sleep(50);return d;}Thread.sleep(20);}throw new AssertionError("timeout");}
 String create(){return ((Map<?,?>)drafts.create(new DraftModels.Create(analysis,0,"입문자 대상으로"))).get("id").toString();}
 @Test void writesOnlySelectedOriginalAndSanitizesThenPublishesOnce()throws Exception{
  String id=create();var d=finish(id);assertThat(d.get("status")).isEqualTo("READY");
  assertThat(((JsonNode)d.get("result")).path("html").asText()).isEqualTo("<p>설명</p>");
  var source=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(originals).read(source.capture());assertThat(source.getValue().path("id").asText()).isEqualTo("a");
  var prompt=org.mockito.ArgumentCaptor.forClass(String.class);verify(runner).analyze(prompt.capture(),any(),any());assertThat(prompt.getValue()).contains("검증한 원문 내용","입문자 대상으로").doesNotContain("다른 소재");
  when(publisher.send(anyMap())).thenReturn(new TistoryPublisher.Result("SAVED_PRIVATE","확인","https://test.tistory.com/1"));
  drafts.publish(id,new DraftModels.Publish("https://test.tistory.com"));assertThat(finish(id).get("status")).isEqualTo("SAVED_PRIVATE");
  assertThatThrownBy(()->drafts.publish(id,new DraftModels.Publish("https://test.tistory.com"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  var payload=org.mockito.ArgumentCaptor.forClass(Map.class);verify(publisher).send(payload.capture());assertThat(payload.getValue().get("html").toString()).contains("https://example.com/a").doesNotContain("example.com/b");
 }
 @Test void ambiguousSendCannotBeRepeated()throws Exception{
  String id=create();finish(id);when(publisher.send(anyMap())).thenThrow(new java.io.IOException());drafts.publish(id,new DraftModels.Publish("https://test.tistory.com"));
  assertThat(finish(id).get("status")).isEqualTo("UNKNOWN");assertThatThrownBy(()->drafts.publish(id,new DraftModels.Publish("https://test.tistory.com"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);verify(publisher,times(1)).send(anyMap());
 }
 @Test void invalidIssueAndEmptyOrActiveHtmlAreRejected()throws Exception{
  assertThatThrownBy(()->drafts.create(new DraftModels.Create(analysis,9,""))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  String id=create();finish(id);assertThatThrownBy(()->drafts.save(id,new DraftModels.Content("제목","<script>x</script>","개발",List.of(),List.of()))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
 }
}
