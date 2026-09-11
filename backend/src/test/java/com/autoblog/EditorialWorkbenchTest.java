package com.autoblog;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:editorialtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE","app.youtube-key=","app.naver-client-id=","app.naver-client-secret="})
class EditorialWorkbenchTest {
 @Autowired Store store;@Autowired EditorialWorkbench workbench;@Autowired SourceDiscovery discovery;
 @MockitoBean AnalysisRunner runner;
 @MockitoBean com.autoblog.collector.SafeHttp http;
 @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
 String topic;
 @BeforeEach void setup(){reset(runner,http);store.jdbc().update("DELETE FROM topics");store.jdbc().update("DELETE FROM articles");topic=UUID.randomUUID().toString();String now=Instant.now().toString();
  store.jdbc().update("INSERT INTO topics(id,name,description,instructions,keywords,exclusions,tags,language,region,active,schedule_enabled,cron,timezone,created_at,updated_at) VALUES(?,'AI','','','[\"AI\",\"LLM\"]','[\"광고\"]','[]','ko','KR',TRUE,FALSE,'0 0 9 * * *','Asia/Seoul',?,?)",topic,now,now);
 }
 void article(String title,String status,String signals,int age){String id=UUID.randomUUID().toString(),url="https://example.com/"+id;
  store.jdbc().update("INSERT INTO articles(id,canonical_url,url_hash,title,url,media,source_name,author,external_id,excerpt,coverage,published_at,collected_at,signals) VALUES(?,?,?,?,?,'BLOG','출처','','',?,'EXCERPT',?,?,?)",id,url,id,title,url,"근거 설명 ".repeat(50),Instant.now().minusSeconds(age*86400L).toString(),Instant.now().toString(),signals);
  store.jdbc().update("INSERT INTO topic_articles(topic_id,article_id,matched_keywords,status) VALUES(?,?,'[]',?)",topic,id,status);
 }
 @Test void discoveryNeedsNoManualWebsiteAndDoesNotDuplicateOrEnableUnavailableModules(){
  assertThat(discovery.configure(topic)).hasSize(2);assertThat(discovery.configure(topic)).isEmpty();
  assertThat(store.sources(topic)).allMatch(s->s.type().equals("HN_SEARCH")&&s.channelId().isEmpty());
 }
 @Test void rankingUsesObservedSignalsAndExcludesHiddenOldAndSubstringMatches(){
  article("AI 모델 배포","UNREAD","{\"points\":100,\"comments\":20}",1);
  article("AI 비용 관리","UNREAD","{}",1);article("AI 숨김","HIDDEN","{}",1);article("AI 옛날","UNREAD","{}",40);article("Daily railway","UNREAD","{}",1);article("AI 광고","UNREAD","{}",1);
  var ranked=workbench.candidates(topic);assertThat(ranked).hasSize(2);assertThat(ranked.get(0).get("title")).isEqualTo("AI 모델 배포");
  assertThat(ranked.get(1).get("reasons").toString()).contains("관심 신호 미확인");
 }
 @Test void preferencePersistsAndDefaultsToManual(){assertThat(workbench.settings(topic).get("automatic")).isEqualTo(false);workbench.settings(topic,"일반 독자",true);assertThat(workbench.settings(topic).get("audience")).isEqualTo("일반 독자");assertThat(discovery.automatic(topic)).isTrue();}
 @Test void oneActionDiscoversCollectsAndStartsEditorialAnalysis()throws Exception{
  var hit=Map.of("title","AI 모델 운영","url","https://example.com/story","objectID","123","author","writer","story_text","원문 설명 ".repeat(60),"created_at_i",Instant.now().getEpochSecond(),"points",20,"num_comments",4);
  when(http.get(anyString(),anyMap())).thenReturn(json.writeValueAsBytes(Map.of("hits",List.of(hit))));
  when(runner.analyze(anyString(),any(),any())).thenAnswer(call->{String prompt=call.getArgument(0);var input=json.readTree(prompt.substring(prompt.indexOf("DATA:\n")+6));String source=input.path("sources").get(0).path("id").asText();
   return new AnalysisRunner.Output(json.writeValueAsString(Map.of("issues",List.of(),"excluded",List.of(Map.of("sourceId",source,"reason","검증용 보류")))),1L,1L,0L);});
  workbench.start(topic);
  boolean finished=false;for(int i=0;i<150;i++){var runs=store.rows("SELECT analysis_id FROM editorial_runs WHERE topic_id=?",topic);if(!runs.isEmpty()&&runs.get(0).get("analysisId")!=null){var jobs=store.rows("SELECT status FROM analysis_jobs WHERE id=?",runs.get(0).get("analysisId"));if(jobs.get(0).get("status").equals("SUCCESS")){finished=true;break;}}Thread.sleep(30);}
  assertThat(finished).isTrue();assertThat(store.topic(topic).articleCount()).isEqualTo(1);assertThat(workbench.candidates(topic).get(0).get("signals").toString()).contains("points=20");verify(runner,times(1)).analyze(anyString(),any(),any());
 }
}
