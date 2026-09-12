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
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class DraftIntegrationTest {
 @Autowired org.springframework.test.web.servlet.MockMvc mvc;
 @Autowired Store store;@Autowired DraftService drafts;@Autowired ObjectMapper json;
 @MockitoBean AnalysisRunner runner;@MockitoBean OriginalReader originals;@MockitoBean TistoryPublisher publisher;
 @MockitoBean CommonsImages imageSearch;
 String analysis;
 @BeforeEach void setup()throws Exception{
  reset(runner,originals,publisher,imageSearch);when(imageSearch.search(any())).thenReturn(new CommonsImages.Search(List.of(),"후보 없음"));store.jdbc().update("DELETE FROM topics");String topic=UUID.randomUUID().toString(),now=Instant.now().toString();analysis=UUID.randomUUID().toString();
  store.jdbc().update("INSERT INTO topics(id,name,description,instructions,keywords,exclusions,tags,language,region,active,schedule_enabled,cron,timezone,created_at,updated_at) VALUES(?,'개발','','','[]','[]','[]','ko','KR',TRUE,FALSE,'0 0 9 * * *','Asia/Seoul',?,?)",topic,now,now);
  String input="{\"sources\":[{\"id\":\"a\",\"title\":\"원문\",\"url\":\"https://example.com/a\",\"excerpt\":\"근거\"},{\"id\":\"b\",\"title\":\"다른 소재\",\"url\":\"https://example.com/b\"}]}";
  store.jdbc().update("INSERT INTO analysis_jobs(id,topic_id,request_hash,mode,direction,status,article_count,input_chars,input_json,result_json,message,created_at) VALUES(?,?,'hash','QUICK','','SUCCESS',2,100,?,?,'',?)",analysis,topic,input,"{\"issues\":[{\"title\":\"소재\",\"sourceIds\":[\"a\"]}]}",now);
  when(originals.read(any())).thenReturn(Map.of("id","a","title","원문","url","https://example.com/a","text","검증한 원문 내용","coverage","ORIGINAL_EXTRACT","message","추출 완료"));
  when(runner.analyze(anyString(),any(),any())).thenReturn(new AnalysisRunner.Output("{\"title\":\"블로그 제목\",\"html\":\"<p>설명</p><script>alert(1)</script><img src=x>\",\"category\":\"개발\",\"tags\":[\"AI\"],\"checks\":[\"검수하세요\"]}",200L,100L,0L));
 }
 Map<String,Object> finish(String id)throws Exception{for(int i=0;i<200;i++){var d=drafts.get(id);if(!Set.of("WRITING","SENDING").contains(d.get("status"))){Thread.sleep(50);return d;}Thread.sleep(20);}throw new AssertionError("timeout");}
 String create(){return ((Map<?,?>)drafts.create(new DraftModels.Create(analysis,0,"입문자 대상으로"))).get("id").toString();}
 @Test void automationItemReusesDurableDraftWithoutAnotherModelCall()throws Exception{
  String topic=store.rows("SELECT topic_id FROM analysis_jobs WHERE id=?",analysis).get(0).get("topicId").toString(),run=UUID.randomUUID().toString(),item=UUID.randomUUID().toString(),now=Instant.now().toString();
  store.jdbc().update("INSERT INTO autopilot_runs(id,topic_id,blog_url,status,stage,message,created_at,updated_at) VALUES(?,?,'https://test.tistory.com','RUNNING','WRITE','',?,?)",run,topic,now,now);
  store.jdbc().update("INSERT INTO autopilot_items(id,run_id,issue_index,title,category) VALUES(?,?,0,'소재','오늘의 AI 뉴스')",item,run);
  var request=new DraftModels.Create(analysis,0,"자동 작성");String id=((Map<?,?>)drafts.create(request,item)).get("id").toString();finish(id);
  assertThat(((Map<?,?>)drafts.create(request,item)).get("id")).isEqualTo(id);verify(runner,times(1)).analyze(anyString(),any(),any());
 }
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
 @Test void unsavedEditorCanBeEditedAndRetriedWithoutGeneratingAgain()throws Exception{
  String id=create();finish(id);
  when(publisher.send(anyMap())).thenReturn(new TistoryPublisher.Result("EDITOR_READY","미저장","https://test.tistory.com/manage/posts/"),new TistoryPublisher.Result("SAVED_PRIVATE","저장","https://test.tistory.com/1"));
  drafts.publish(id,new DraftModels.Publish("https://test.tistory.com"));finish(id);
  drafts.save(id,new DraftModels.Content("수정한 제목","<p>설명</p>","개발",List.of(),List.of()));
  drafts.publish(id,new DraftModels.Publish("https://test.tistory.com"));assertThat(finish(id).get("status")).isEqualTo("SAVED_PRIVATE");
  verify(runner,times(1)).analyze(anyString(),any(),any());verify(publisher,times(2)).send(anyMap());
  assertThatThrownBy(()->drafts.retry(id,true)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
 }
 @Test void unknownRequiresExplicitNotSavedConfirmation()throws Exception{
  String id=create();finish(id);when(publisher.send(anyMap())).thenThrow(new java.io.IOException());
  drafts.publish(id,new DraftModels.Publish("https://test.tistory.com"));finish(id);
  assertThatThrownBy(()->drafts.retry(id,false)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  assertThat(((Map<?,?>)drafts.retry(id,true)).get("status")).isEqualTo("READY");
 }
 @Test void fullOriginalReachesModelAndUnavailableOriginalStopsWriting()throws Exception{
  String full="전체 본문 ".repeat(2500)+"마지막 결론";
  when(originals.read(any())).thenReturn(Map.of("text",full,"coverage","ORIGINAL_EXTRACT"));
  assertThat(finish(create()).get("status")).isEqualTo("READY");
  verify(runner).analyze(contains(full),any(),any());
  clearInvocations(runner);
  when(originals.read(any())).thenReturn(Map.of("text","발췌문","coverage","EXCERPT"));
  assertThat(finish(create()).get("status")).isEqualTo("FAILED");
  verify(runner,never()).analyze(anyString(),any(),any());
 }
 @Test void httpSaveAllowsNoCategoryAndExplainsInvalidFields()throws Exception{
  String id=create();finish(id);
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/drafts/"+id)
    .contentType("application/json").content(json.writeValueAsString(new DraftModels.Content("제목","<p>본문</p>","",List.of(),List.of()))))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/drafts/"+id)
    .contentType("application/json").content(json.writeValueAsString(new DraftModels.Content("","<p>본문</p>","",List.of(),List.of()))))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("제목: 값을 입력해 주세요."));
  var image=new DraftModels.Image("https://example.com/a.png","https://example.com/a","작가","CC BY 4.0","https://creativecommons.org/licenses/by/4.0/","설명",1,false);
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/drafts/"+id)
    .contentType("application/json").content(json.writeValueAsString(new DraftModels.Content("제목","<p>본문</p>","",List.of(),List.of(),List.of(image)))))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value(org.hamcrest.Matchers.containsString("사용 조건 확인")));
 }
 @Test void invalidIssueAndEmptyOrActiveHtmlAreRejected()throws Exception{
  assertThatThrownBy(()->drafts.create(new DraftModels.Create(analysis,9,""))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  String id=create();finish(id);assertThatThrownBy(()->drafts.save(id,new DraftModels.Content("제목","<script>x</script>","개발",List.of(),List.of()))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
 }
 @Test void confirmedImagesPersistIntoPreviewAndPublisherPayload()throws Exception{
  String id=create();finish(id);
  var image=new DraftModels.Image("https://example.com/image.jpg","https://example.com/source","제작자","CC BY 4.0","https://creativecommons.org/licenses/by/4.0/","기능 비교",1,true);
  drafts.save(id,new DraftModels.Content("제목","<p>설명</p><table><tr><td>비교</td></tr></table>","개발",List.of(),List.of(),List.of(image)));
  assertThat(drafts.get(id).get("previewHtml").toString()).contains("<img","기능 비교","CC BY 4.0","<table ");
  when(publisher.send(anyMap())).thenReturn(new TistoryPublisher.Result("SAVED_PRIVATE","확인","https://test.tistory.com/1"));
  drafts.publish(id,new DraftModels.Publish("https://test.tistory.com"));finish(id);
  var payload=org.mockito.ArgumentCaptor.forClass(Map.class);verify(publisher).send(payload.capture());
  assertThat(payload.getValue().get("html").toString()).contains("https://example.com/image.jpg","https://creativecommons.org/licenses/by/4.0/");
 }
 @Test void unconfirmedRightsAndTooManyImagesAreRejected()throws Exception{
  String id=create();finish(id);
  var image=new DraftModels.Image("https://example.com/image.jpg","https://example.com/source","제작자","조건","https://example.com/license","설명",1,false);
  assertThatThrownBy(()->drafts.save(id,new DraftModels.Content("제목","<p>설명</p>","개발",List.of(),List.of(),List.of(image)))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  assertThatThrownBy(()->drafts.save(id,new DraftModels.Content("제목","<p>설명</p>","개발",List.of(),List.of(),Collections.nCopies(4,image)))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
 }
 @Test void disconnectedChromeDoesNotConsumeReadyDraft()throws Exception{
  String id=create();finish(id);
  doThrow(Store.bad("Chrome 연결 필요")).when(publisher).ensureConnected();
  assertThatThrownBy(()->drafts.publish(id,new DraftModels.Publish("https://test.tistory.com"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  assertThat(drafts.get(id).get("status")).isEqualTo("READY");verify(publisher,never()).send(anyMap());
 }
 @Test void newDraftAutomaticallyIncludesSelectedCatalogImage()throws Exception{
  when(imageSearch.search(any())).thenReturn(new CommonsImages.Search(List.of(new CommonsImages.Candidate("image1","설명 도식","개념 설명","https://example.com/image.png","https://example.com/source","Author","CC BY 4.0","https://creativecommons.org/licenses/by/4.0/")),"후보 있음"));
  when(runner.analyze(anyString(),any(),any())).thenReturn(new AnalysisRunner.Output("{\"title\":\"제목\",\"html\":\"<p>첫 설명</p><p>다음 설명</p>\",\"category\":\"기술\",\"tags\":[],\"checks\":[],\"imageSelections\":[{\"id\":\"image1\",\"caption\":\"개념 설명\",\"afterParagraph\":1}]}",1L,1L,0L));
  var d=finish(create());assertThat(d.get("status")).isEqualTo("READY");
  var doc=org.jsoup.Jsoup.parse(d.get("previewHtml").toString());
  assertThat(doc.select("img")).hasSize(1);assertThat(doc.selectFirst("p").nextElementSibling().tagName()).isEqualTo("figure");
  assertThat(((JsonNode)d.get("result")).path("images").size()).isEqualTo(1);
 }
}
