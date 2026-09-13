package com.autoblog;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Durable orchestration. Browser sends with an uncertain outcome are never replayed. */
@Service
public class AutopilotService {
 private final Store store;private final CollectionService collection;private final SourceDiscovery discovery;
 private final TriageService triage;private final EditorialWorkbench editorial;private final AnalysisService analysis;
 private final DraftService drafts;private final TistoryPublisher publisher;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();private volatile boolean active;
 private static final Set<String> CATEGORIES=Set.of("오늘의 AI 뉴스","AI 도구 사용기","업무에 쓰는 AI","AI 개발과 자동화","AI 쉽게 이해하기");
 public AutopilotService(Store s,CollectionService c,SourceDiscovery d,TriageService t,EditorialWorkbench e,AnalysisService a,DraftService drafts,TistoryPublisher p){store=s;collection=c;discovery=d;triage=t;editorial=e;analysis=a;this.drafts=drafts;publisher=p;}
 @PostConstruct void recover(){store.jdbc().update("UPDATE autopilot_runs SET status='PAUSED',message='서버 재시작으로 멈췄습니다. 저장 상태를 확인하고 이어서 실행하세요.',updated_at=? WHERE status='RUNNING'",Instant.now().toString());}
 @PreDestroy void close(){worker.shutdownNow();}
 public Map<String,Object> board(String topic){store.topic(topic);var runs=store.rows("SELECT * FROM autopilot_runs WHERE topic_id=? ORDER BY created_at DESC LIMIT 30",topic);for(var r:runs){r.put("items",items(r.get("id").toString()));r.put("logs",store.rows("SELECT status,stage,message,created_at FROM autopilot_logs WHERE run_id=? ORDER BY created_at,id",r.get("id")));}return Map.of("active",active,"runs",runs);}
 List<Map<String,Object>> items(String run){return store.rows("SELECT i.*,b.id draft_id,b.status draft_status,b.message draft_message,b.remote_url FROM autopilot_items i LEFT JOIN blog_drafts b ON b.automation_item_id=i.id WHERE i.run_id=? ORDER BY i.issue_index",run);}
 Map<String,Object> run(String id){var rows=store.rows("SELECT * FROM autopilot_runs WHERE id=?",id);if(rows.isEmpty())throw Store.missing("자동 작성 실행");return rows.get(0);}
 public synchronized Map<String,Object> start(String topic,String blog){
  store.topic(topic);if(blog==null||!blog.matches("https://[a-zA-Z0-9-]+\\.tistory\\.com/?"))throw Store.bad("티스토리 블로그 주소를 확인하세요.");
  var unfinished=store.rows("SELECT id,topic_id FROM autopilot_runs WHERE status IN ('RUNNING','PAUSED') ORDER BY created_at DESC");
  if(!unfinished.isEmpty()){if(topic.equals(unfinished.get(0).get("topicId")))return Map.of("id",unfinished.get(0).get("id"),"existing",true);throw Store.bad("다른 분야의 자동 작성을 먼저 완료해 주세요.");}
  if(active||editorial.busy(topic)||drafts.isBusy())throw Store.bad("다른 작업이 진행 중입니다.");publisher.ensureConnected();
  String id=UUID.randomUUID().toString(),now=Instant.now().toString();
  store.jdbc().update("INSERT INTO autopilot_runs(id,topic_id,blog_url,status,stage,message,created_at,updated_at) VALUES(?,?,?,'RUNNING','COLLECT','수집부터 최대 5개 글의 비공개 저장까지 진행합니다.',?,?)",id,topic,blog.replaceAll("/$",""),now,now);launch(id);return Map.of("id",id);
 }
 public synchronized Object resume(String topic,String id){var r=run(id);if(!topic.equals(r.get("topicId")))throw Store.missing("분야의 자동 작성 실행");if(active||!r.get("status").equals("PAUSED"))throw Store.bad("일시 중지한 실행만 이어서 진행할 수 있습니다.");if(editorial.busy(topic))throw Store.bad("다른 작업이 진행 중입니다.");publisher.ensureConnected();state(id,"RUNNING",r.get("stage").toString(),"저장된 진행 상태부터 이어서 실행합니다.");launch(id);return Map.of("id",id);}
 void launch(String id){log(id,"RUNNING",ref(id,"stage"),"실행 시작: "+id);active=true;worker.submit(()->execute(id));}
 Map<String,Object> paused(String topic,String id){var r=run(id);if(!topic.equals(r.get("topicId")))throw Store.missing("분야의 자동 작성 실행");if(active||drafts.isBusy()||!"PAUSED".equals(r.get("status")))throw Store.bad("작업이 멈춘 뒤 실행을 정리할 수 있습니다.");return r;}
 public synchronized Object cancel(String topic,String id){var r=paused(topic,id);state(id,"CANCELLED",r.get("stage").toString(),"실행을 종료했습니다. 기존 초안과 티스토리 글은 보존됩니다. 새 자동 실행을 시작할 수 있습니다.");return Map.of("id",id);}
 public synchronized Object skip(String topic,String id,String itemId){paused(topic,id);var matches=items(id).stream().filter(i->itemId.equals(i.get("id"))).toList();if(matches.isEmpty())throw Store.missing("자동 작성 항목");var item=matches.get(0);if(Set.of("WRITING","SENDING","SAVED_PRIVATE").contains(Objects.toString(item.get("draftStatus"),"")))throw Store.bad("진행 중이거나 저장 완료된 글은 건너뛸 수 없습니다.");store.jdbc().update("UPDATE autopilot_items SET skipped=TRUE WHERE id=? AND run_id=?",itemId,id);return Map.of("id",itemId);}
 void log(String id,String status,String stage,String message){store.jdbc().update("INSERT INTO autopilot_logs(id,run_id,status,stage,message,created_at) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),id,status,stage,message,Instant.now().toString());}
 void state(String id,String status,String stage,String message){store.jdbc().update("UPDATE autopilot_runs SET status=?,stage=?,message=?,updated_at=? WHERE id=?",status,stage,message,Instant.now().toString(),id);log(id,status,stage,message);}
 String ref(String id,String key){return Objects.toString(run(id).get(key),"");}
 void reference(String id,String column,String value){if(!Set.of("collection_id","triage_id","editorial_id","analysis_id").contains(column))throw new IllegalArgumentException();store.jdbc().update("UPDATE autopilot_runs SET "+column+"=?,updated_at=? WHERE id=?",value,Instant.now().toString(),id);}
 Map<String,Object> await(Supplier<Map<String,Object>> read,Set<String> pending)throws Exception{long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(25);while(true){if(Thread.currentThread().isInterrupted())throw new InterruptedException();var row=read.get();if(!pending.contains(row.get("status")))return row;if(System.nanoTime()>deadline)throw Store.bad("작업 대기 시간이 길어 중지했습니다. 실행 상태를 확인한 뒤 이어서 실행하세요.");Thread.sleep(300);}}
 Map<String,Object> row(String table,String id){if(!Set.of("runs","triage_runs","editorial_runs").contains(table))throw new IllegalArgumentException();return store.rows("SELECT * FROM "+table+" WHERE id=?",id).stream().findFirst().orElseThrow();}
 static String category(JsonNode issue){String c=issue.path("category").asText();if(CATEGORIES.contains(c))return c;return switch(c){case "AI 뉴스"->"오늘의 AI 뉴스";case "AI 도구"->"AI 도구 사용기";case "업무 활용"->"업무에 쓰는 AI";case "개발·자동화"->"AI 개발과 자동화";default->"AI 쉽게 이해하기";};}
 void execute(String id){try{
  var run=run(id);String topic=run.get("topicId").toString(),blog=run.get("blogUrl").toString();
  if(ref(id,"collectionId").isEmpty()){discovery.configure(topic);reference(id,"collection_id",collection.start(topic,"AUTOPILOT"));}
  String collectionId=ref(id,"collectionId");var collected=await(()->row("runs",collectionId),Set.of("QUEUED","RUNNING"));
  if(!Set.of("SUCCESS","PARTIAL").contains(collected.get("status"))){reference(id,"collection_id",null);throw Store.bad("수집에 실패했습니다. 출처 상태를 수정한 뒤 이어서 실행하세요.");}
  if(ref(id,"editorialId").isEmpty()){
   state(id,"RUNNING","TRIAGE","자료 최대 30건을 한글 요약·등급으로 선별합니다.");
   if(ref(id,"triageId").isEmpty())reference(id,"triage_id",triage.start(topic,30).get("id").toString());
   String triageId=ref(id,"triageId");var screened=await(()->row("triage_runs",triageId),Set.of("QUEUED","RUNNING"));
   // Worker flags clear just after terminal rows; avoid racing the next service's admission check.
   while(triage.busy(topic))Thread.sleep(100);
   if(!"SUCCESS".equals(screened.get("status"))){reference(id,"triage_id",null);throw Store.bad("1차 분류가 중단됐습니다. AI 연결을 확인한 뒤 이어서 실행하면 남은 자료만 처리합니다.");}
   state(id,"RUNNING","ANALYZE","1·2티어 상위 후보 5건의 원문을 확인하고 기획을 분석합니다.");
   reference(id,"editorial_id",editorial.screen(topic,collectionId).get("id").toString());
  }
  String editorialId=ref(id,"editorialId");var selected=await(()->row("editorial_runs",editorialId),Set.of("SCREENING"));
  if("EMPTY".equals(selected.get("status"))){state(id,"COMPLETE","DONE",Objects.toString(selected.get("message"),"추천 가능한 원문이 없습니다."));return;}
  if(selected.get("analysisId")==null){reference(id,"editorial_id",null);throw Store.bad("원문 분석 준비에 실패했습니다. 이어서 실행하면 원문을 다시 확인합니다.");}
  String analysisId=selected.get("analysisId").toString();reference(id,"analysis_id",analysisId);
  var analyzed=await(()->analysis.job(analysisId),Set.of("QUEUED","RUNNING"));
  if(!"SUCCESS".equals(analyzed.get("status"))){reference(id,"editorial_id",null);throw Store.bad("집중 분석이 중단됐습니다. AI 연결 또는 사용량을 확인하고 이어서 실행하세요.");}
  {
   JsonNode result=(JsonNode)analyzed.get("result");List<Integer> indices=new ArrayList<>();for(int i=0;i<result.path("issues").size();i++)if("RECOMMEND".equals(result.path("issues").get(i).path("editorial").path("decision").asText()))indices.add(i);
   indices.sort(Comparator.<Integer>comparingInt(i->result.path("issues").get(i).path("editorial").path("priority").asInt()).reversed());
   for(int index:indices.stream().limit(5).toList()){if(items(id).stream().anyMatch(i->((Number)i.get("issueIndex")).intValue()==index))continue;var issue=result.path("issues").get(index);store.jdbc().update("INSERT INTO autopilot_items(id,run_id,issue_index,title,category) VALUES(?,?,?,?,?)",UUID.randomUUID().toString(),id,index,issue.path("title").asText(),category(issue));}
  }
  int saved=0,failed=0,skipped=0;
  for(var item:items(id)){
   if(Boolean.TRUE.equals(item.get("skipped"))){skipped++;log(id,"RUNNING","WRITE","건너뜀: "+item.get("title"));continue;}
   String itemId=item.get("id").toString();int index=((Number)item.get("issueIndex")).intValue();String category=item.get("category").toString();
   String draftId=Objects.toString(item.get("draftId"),"");
   if(draftId.isEmpty()){state(id,"RUNNING","WRITE",(saved+1)+"번째 글을 작성합니다. 분류: "+category);var created=(Map<?,?>)drafts.create(new DraftModels.Create(analysisId,index,"자동 비공개 검토용 글입니다. 카테고리는 반드시 '"+category+"'로 작성하세요. 원문 전체를 읽고 담백한 합니다·입니다체로 배경과 실용적 인사이트를 설명하세요."),itemId);draftId=created.get("id").toString();}
   final String current=draftId;var draft=await(()->drafts.get(current),Set.of("WRITING","SENDING"));while(drafts.isBusy())Thread.sleep(100);String status=draft.get("status").toString();
   if(status.equals("SAVED_PRIVATE")){saved++;log(id,"RUNNING","SAVE","기존 저장 확인: "+item.get("title"));continue;}
   if(status.equals("UNKNOWN"))throw Store.bad("저장 여부가 불확실한 글이 있습니다. 티스토리에서 확인 후 해당 초안을 처리해야 이어갈 수 있습니다. 자동 재전송하지 않습니다.");
   if(status.equals("FAILED")){failed++;log(id,"FAILED","WRITE","작성 실패: "+item.get("title")+" · "+draft.get("message"));continue;}
   if(status.equals("EDITOR_READY")){drafts.retry(current,false);draft=drafts.get(current);}
   if(!Set.of("READY","EDITOR_READY").contains(draft.get("status")))throw Store.bad("초안 상태를 확인해 주세요.");
   JsonNode content=(JsonNode)draft.get("result");DraftModels.Content typed=store.mapper().treeToValue(content,DraftModels.Content.class);
   drafts.save(current,new DraftModels.Content(typed.title(),typed.html(),category,typed.tags(),typed.checks(),typed.images()));
   state(id,"RUNNING","SAVE",(saved+1)+"번째 글을 기존 Chrome에서 비공개로 저장합니다.");drafts.publish(current,new DraftModels.Publish(blog));
   var published=await(()->drafts.get(current),Set.of("SENDING"));
   while(drafts.isBusy())Thread.sleep(100);
   if(!"SAVED_PRIVATE".equals(published.get("status")))throw Store.bad("티스토리 저장 확인이 필요합니다. "+published.get("message"));saved++;log(id,"RUNNING","SAVE","비공개 저장 완료: "+item.get("title"));
  }
  state(id,failed>0||skipped>0?"PARTIAL":"COMPLETE","DONE",saved+"개 글을 티스토리에 비공개 저장했습니다. 건너뜀 "+skipped+"건."+(failed>0?" 작성 실패 "+failed+"건은 초안에서 확인하세요.":" 검토 후 공개로 전환하면 됩니다."));
 }catch(Exception e){state(id,"PAUSED",ref(id,"stage"),e instanceof org.springframework.web.server.ResponseStatusException r?r.getReason():"자동 작성이 중단됐습니다. 완료된 글은 유지하며 저장 상태 확인 후 이어서 실행할 수 있습니다.");}finally{active=false;}}
}
