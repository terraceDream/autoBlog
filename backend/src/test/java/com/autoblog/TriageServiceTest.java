package com.autoblog;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:triagetest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"})
class TriageServiceTest {
 @Autowired Store store;@Autowired TriageService triage;@Autowired EditorialWorkbench workbench;@Autowired ObjectMapper json;
 @MockitoBean AnalysisRunner runner;@MockitoBean OriginalReader originals;
 String topic;
 @BeforeEach void setup(){reset(runner,originals);store.jdbc().update("DELETE FROM topics");store.jdbc().update("DELETE FROM articles");topic=UUID.randomUUID().toString();String now=Instant.now().toString();store.jdbc().update("INSERT INTO topics(id,name,description,instructions,keywords,exclusions,tags,language,region,active,schedule_enabled,cron,timezone,created_at,updated_at) VALUES(?,'AI','','','[\"AI\"]','[]','[]','ko','KR',TRUE,FALSE,'0 0 9 * * *','Asia/Seoul',?,?)",topic,now,now);}
 String article(){String id=UUID.randomUUID().toString(),now=Instant.now().toString();store.jdbc().update("INSERT INTO articles(id,canonical_url,url_hash,title,url,media,source_name,author,external_id,excerpt,coverage,published_at,collected_at) VALUES(?,?,?,'AI workflow release',?,'BLOG','출처','','',?,'EXCERPT',?,?)",id,"https://example.com/"+id,id,"https://example.com/"+id,"Concrete description of an AI workflow. ".repeat(50),now,now);store.jdbc().update("INSERT INTO topic_articles(topic_id,article_id,matched_keywords,status) VALUES(?,?,'[]','UNREAD')",topic,id);return id;}
 void respond()throws Exception{when(runner.analyze(anyString(),any(),any())).thenAnswer(call->{String prompt=call.getArgument(0);var sources=json.readTree(prompt.substring(prompt.indexOf("DATA:\n")+6)).path("sources");var arr=json.createArrayNode();for(var source:sources){assertThat(source.path("excerpt").asText().length()).isLessThanOrEqualTo(900);var item=arr.addObject().put("id",source.path("id").asText()).put("tier","T1").put("score",85).put("category","업무 활용").put("confidence","MEDIUM");for(String k:List.of("titleKo","summaryKo","reason","readerBenefit","angle","missing"))item.put(k,"업무 자동화의 적용 조건을 설명하는 자료입니다. 원문은 추가 확인해야 합니다.");}return new AnalysisRunner.Output(json.createObjectNode().set("items",arr).toString(),100L,30L,0L);});}
 void waitRun(String id)throws Exception{for(int i=0;i<200;i++){String status=store.rows("SELECT status FROM triage_runs WHERE id=?",id).get(0).get("status").toString();if(Set.of("SUCCESS","FAILED").contains(status)&&!triage.busy(topic))return;Thread.sleep(20);}fail("triage did not finish");}
 @Test void capsInputCachesResultsAndSearchesKorean()throws Exception{String id=article();respond();String run=triage.start(topic,15).get("id").toString();waitRun(run);var result=triage.inbox(topic,"T1","업무 활용","자동화","priority",0);assertThat(result.get("total")).isEqualTo(1L);assertThat(result.get("items").toString()).contains("업무 자동화");
  // Reading the full original later must not invalidate the cheap screening cache.
  store.jdbc().update("UPDATE articles SET excerpt=?,coverage='ORIGINAL_EXTRACT' WHERE id=?","Entire original ".repeat(3000),id);
  waitRun(triage.start(topic,15).get("id").toString());verify(runner,times(1)).analyze(anyString(),any(),any());
  assertThat(triage.inbox(topic,"T2","","","priority",0).get("total")).isEqualTo(0L);
 }
 @Test void badCitationsFailWithoutSavingOrSpendingAgainAutomatically()throws Exception{article();when(runner.analyze(anyString(),any(),any())).thenReturn(new AnalysisRunner.Output("{\"items\":[{\"id\":\"foreign\"}]}",42L,10L,0L));String run=triage.start(topic,15).get("id").toString();waitRun(run);assertThat(store.rows("SELECT status,input_tokens FROM triage_runs WHERE id=?",run).get(0)).containsEntry("status","FAILED").containsEntry("inputTokens",42L);assertThat(triage.inbox(topic,"PENDING","","","priority",0).get("total")).isEqualTo(1L);verify(runner,times(1)).analyze(anyString(),any(),any());}
 @Test void limitLeavesVisiblePendingAndRejectsUnclassifiedFocus()throws Exception{String id=article();article();assertThatThrownBy(()->workbench.screen(topic,null,List.of(id))).hasMessageContaining("1차 분류");respond();waitRun(triage.start(topic,1).get("id").toString());assertThat(triage.inbox(topic,"PENDING","","","priority",0).get("total")).isEqualTo(1L);assertThatThrownBy(()->triage.start(topic,61)).hasMessageContaining("60건");}
 @Test void missingOriginalStopsBeforeExpensiveInference()throws Exception{String id=article();respond();waitRun(triage.start(topic,1).get("id").toString());reset(runner);when(originals.read(any())).thenReturn(Map.of("coverage","EXCERPT","errorCode","HTTP_403","message","접속 거부"));String run=workbench.screen(topic,null,List.of(id)).get("id").toString();boolean done=false;for(int i=0;i<200;i++){if(store.rows("SELECT status FROM editorial_runs WHERE id=?",run).get(0).get("status").equals("EMPTY")){done=true;break;}Thread.sleep(20);}assertThat(done).isTrue();verify(runner,never()).analyze(anyString(),any(),any());assertThat(store.rows("SELECT original_status FROM articles WHERE id=?",id).get(0)).containsEntry("originalStatus","HTTP_403");}
}
