package com.autoblog;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:autopilottest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"})
class AutopilotServiceTest {
 @Autowired Store store;@Autowired AutopilotService auto;@Autowired ObjectMapper json;
 @MockitoBean CollectionService collection;@MockitoBean SourceDiscovery discovery;@MockitoBean TriageService triage;
 @MockitoBean EditorialWorkbench editorial;@MockitoBean AnalysisService analysis;@MockitoBean DraftService drafts;@MockitoBean TistoryPublisher publisher;
 String topic,analysisId;boolean unknown;
 @BeforeEach void setup()throws Exception{reset(collection,discovery,triage,editorial,analysis,drafts,publisher);store.jdbc().update("DELETE FROM topics");topic=UUID.randomUUID().toString();analysisId=UUID.randomUUID().toString();String now=Instant.now().toString();unknown=false;
 store.jdbc().update("INSERT INTO topics(id,name,description,instructions,keywords,exclusions,tags,language,region,active,schedule_enabled,cron,timezone,created_at,updated_at) VALUES(?,'AI','','','[]','[]','[]','ko','KR',TRUE,FALSE,'0 0 9 * * *','Asia/Seoul',?,?)",topic,now,now);
 store.jdbc().update("INSERT INTO analysis_jobs(id,topic_id,request_hash,mode,direction,status,article_count,input_chars,input_json,message,created_at) VALUES(?,?,'hash','FULL','','SUCCESS',5,100,'{}','',?)",analysisId,topic,now);
 var issues=json.createArrayNode();for(int i=0;i<6;i++){var issue=issues.addObject().put("title","자동화 검증 소재 "+i).put("category",i==0?"업무 활용":"AI 뉴스");issue.putObject("editorial").put("decision",i==5?"HOLD":"RECOMMEND").put("priority",90-i);}
 when(analysis.job(analysisId)).thenReturn(Map.of("status","SUCCESS","result",json.createObjectNode().set("issues",issues)));
 when(collection.start(eq(topic),eq("AUTOPILOT"))).thenAnswer(c->{String id=UUID.randomUUID().toString();store.jdbc().update("INSERT INTO runs(id,topic_id,trigger_type,status,started_at,message) VALUES(?,?,'AUTOPILOT','SUCCESS',?,'')",id,topic,now);return id;});
 when(triage.start(topic,30)).thenAnswer(c->{String id=UUID.randomUUID().toString();store.jdbc().update("INSERT INTO triage_runs(id,topic_id,status,message,requested,created_at) VALUES(?,?,'SUCCESS','',30,?)",id,topic,now);return Map.of("id",id);});
 when(editorial.screen(eq(topic),anyString())).thenAnswer(c->{String id=UUID.randomUUID().toString();store.jdbc().update("INSERT INTO editorial_runs(id,topic_id,analysis_id,status,message,created_at) VALUES(?,?,?,'ANALYZING','',?)",id,topic,analysisId,now);return Map.of("id",id);});
 when(drafts.create(any(),anyString())).thenAnswer(c->{DraftModels.Create req=c.getArgument(0);String item=c.getArgument(1),id=UUID.randomUUID().toString();store.jdbc().update("INSERT INTO blog_drafts(id,analysis_id,issue_index,direction,status,input_json,message,created_at,updated_at,automation_item_id) VALUES(?,?,?,'','READY','{}','',?,?,?)",id,analysisId,req.issueIndex(),now,now,item);return Map.of("id",id);});
 when(drafts.get(anyString())).thenAnswer(c->{String id=c.getArgument(0);var row=store.rows("SELECT status FROM blog_drafts WHERE id=?",id).get(0);return Map.of("status",row.get("status"),"result",json.valueToTree(new DraftModels.Content("제목","<p>본문</p>","임시",List.of(),List.of())),"message","저장 확인 필요");});
 when(drafts.publish(anyString(),any())).thenAnswer(c->{String id=c.getArgument(0);DraftModels.Publish req=c.getArgument(1);assertThat(req.blogUrl()).isEqualTo("https://plzundrstnd.tistory.com");store.jdbc().update("UPDATE blog_drafts SET status=? WHERE id=?",unknown?"UNKNOWN":"SAVED_PRIVATE",id);return Map.of();});
 }
 void waitEnd(String id)throws Exception{for(int i=0;i<300;i++){if(!auto.run(id).get("status").equals("RUNNING")&&!Boolean.TRUE.equals(auto.board(topic).get("active")))return;Thread.sleep(20);}fail("automation did not finish");}
 @Test void oneClickCreatesFivePrivateDraftsAndNormalizesCategories()throws Exception{String id=auto.start(topic,"https://plzundrstnd.tistory.com/").get("id").toString();waitEnd(id);assertThat(auto.run(id).get("status")).isEqualTo("COMPLETE");assertThat(auto.items(id)).hasSize(5).allMatch(i->i.get("draftStatus").equals("SAVED_PRIVATE"));assertThat(auto.items(id).get(0).get("category")).isEqualTo("업무에 쓰는 AI");verify(drafts,times(5)).publish(anyString(),any());verify(triage,times(1)).start(topic,30);}
 @Test void unknownSendPausesAndResumeNeverRepublishesUncertainDraft()throws Exception{unknown=true;String id=auto.start(topic,"https://plzundrstnd.tistory.com").get("id").toString();waitEnd(id);assertThat(auto.run(id).get("status")).isEqualTo("PAUSED");assertThat(auto.start(topic,"https://plzundrstnd.tistory.com").get("id")).isEqualTo(id);auto.resume(topic,id);waitEnd(id);verify(drafts,times(1)).publish(anyString(),any());verify(drafts,times(1)).create(any(),anyString());
  // User has checked the remote result and marked the existing draft saved.
  store.jdbc().update("UPDATE blog_drafts SET status='SAVED_PRIVATE' WHERE automation_item_id=?",auto.items(id).get(0).get("id"));unknown=false;auto.resume(topic,id);waitEnd(id);assertThat(auto.run(id).get("status")).isEqualTo("COMPLETE");verify(drafts,times(5)).publish(anyString(),any());verify(collection,times(1)).start(topic,"AUTOPILOT");}
 @Test void disconnectedChromeFailsBeforeCollection(){doThrow(Store.bad("Chrome 연결 필요")).when(publisher).ensureConnected();assertThatThrownBy(()->auto.start(topic,"https://plzundrstnd.tistory.com")).hasMessageContaining("Chrome");verifyNoInteractions(collection);assertThat(store.rows("SELECT * FROM autopilot_runs")).isEmpty();}
 @Test void cancelPreservesDraftsAndReleasesNewRun()throws Exception{unknown=true;String id=auto.start(topic,"https://plzundrstnd.tistory.com").get("id").toString();waitEnd(id);var item=auto.items(id).get(0);assertThatThrownBy(()->auto.cancel("wrong-topic",id)).isInstanceOf(RuntimeException.class);auto.cancel(topic,id);assertThat(auto.run(id).get("status")).isEqualTo("CANCELLED");assertThat(auto.items(id).get(0).get("draftId")).isEqualTo(item.get("draftId"));assertThat(auto.items(id).get(0).get("draftStatus")).isEqualTo("UNKNOWN");assertThatThrownBy(()->auto.resume(topic,id)).isInstanceOf(RuntimeException.class);unknown=false;String next=auto.start(topic,"https://plzundrstnd.tistory.com").get("id").toString();waitEnd(next);assertThat(next).isNotEqualTo(id);assertThat(auto.run(next).get("status")).isEqualTo("COMPLETE");}
 @Test void skipUncertainDraftContinuesOtherFourWithoutRepublishingIt()throws Exception{unknown=true;String id=auto.start(topic,"https://plzundrstnd.tistory.com").get("id").toString();waitEnd(id);String item=auto.items(id).get(0).get("id").toString();assertThatThrownBy(()->auto.skip(topic,id,"wrong-item")).isInstanceOf(RuntimeException.class);auto.skip(topic,id,item);unknown=false;auto.resume(topic,id);waitEnd(id);assertThat(auto.run(id).get("status")).isEqualTo("PARTIAL");assertThat(auto.items(id).get(0).get("draftStatus")).isEqualTo("UNKNOWN");assertThat(auto.items(id).get(0).get("skipped")).isEqualTo(true);verify(drafts,times(5)).publish(anyString(),any());verify(collection,times(1)).start(topic,"AUTOPILOT");}
}
