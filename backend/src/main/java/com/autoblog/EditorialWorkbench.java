package com.autoblog;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class EditorialWorkbench {
 private final Store store;private final SourceDiscovery discovery;private final CollectionService collection;private final AnalysisService analysis;private final OriginalReader originals;private final ObjectMapper json;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final TriageService triage;
 public EditorialWorkbench(Store s,SourceDiscovery d,CollectionService c,AnalysisService a,OriginalReader o,ObjectMapper j,TriageService triage){store=s;discovery=d;collection=c;analysis=a;originals=o;json=j;this.triage=triage;}
 @PostConstruct void recover(){store.jdbc().update("UPDATE editorial_runs SET status='FAILED',message='서버 재시작으로 선별이 중단됐습니다. 다시 실행할 수 있습니다.' WHERE status='SCREENING'");}
 @PreDestroy void stop(){worker.shutdownNow();}
 public Map<String,Object> settings(String topic){store.topic(topic);var rows=store.rows("SELECT * FROM editorial_settings WHERE topic_id=?",topic);return rows.isEmpty()?Map.of("audience","AI를 업무에 활용하려는 실무자","automatic",false):rows.get(0);}
 public synchronized Object settings(String topic,String audience,boolean automatic){store.topic(topic);if(audience==null||audience.isBlank()||audience.length()>300)throw Store.bad("독자를 1~300자로 입력해 주세요.");
  if(store.jdbc().update("UPDATE editorial_settings SET audience=?,automatic=? WHERE topic_id=?",audience,automatic,topic)==0)store.jdbc().update("INSERT INTO editorial_settings(topic_id,audience,automatic) VALUES(?,?,?)",topic,audience,automatic);
  triage.refresh(topic);return settings(topic);
 }
 public synchronized Map<String,Object> start(String topic){
  if(busy(topic))throw Store.bad("이미 수집 또는 추천 작업이 진행 중입니다.");
  var prefs=settings(topic);settings(topic,prefs.get("audience").toString(),true);
  return Map.of("runId",collection.start(topic,"EDITORIAL"));
 }
 boolean busy(String topic){return triage.busy(topic)||collection.running(topic)||analysis.running(topic)||analysis.runner.isBusy()||store.jdbc().queryForObject("SELECT COUNT(*) FROM editorial_runs WHERE topic_id=? AND status='SCREENING'",Integer.class,topic)>0;}
 @EventListener public void collected(CollectionService.Finished event){if(store.rows("SELECT trigger_type FROM runs WHERE id=?",event.runId()).stream().anyMatch(r->"AUTOPILOT".equals(r.get("triggerType"))))return;try{triage.start(event.topicId(),30);}catch(org.springframework.web.server.ResponseStatusException e){/* Existing screening owns the queue. Remaining rows are visibly PENDING. */}}
 public synchronized Map<String,Object> screen(String topic,String collectionId){
  return screen(topic,collectionId,List.of());
 }
 public synchronized Map<String,Object> screen(String topic,String collectionId,List<String> ids){
  store.topic(topic);if(busy(topic))throw Store.bad("이미 수집 또는 추천 작업이 진행 중입니다.");
  if(ids==null||ids.size()>5||new HashSet<>(ids).size()!=ids.size())throw Store.bad("집중 분석은 서로 다른 자료 최대 5건입니다.");
  var used=drafted(topic);
  for(String source:ids){var rows=store.rows("SELECT triage_tier,status FROM topic_articles WHERE topic_id=? AND article_id=?",topic,source);if(rows.isEmpty()||rows.get(0).get("status").equals("HIDDEN")||used.contains(source))throw Store.bad("숨김·작성 완료 자료 또는 다른 분야 자료는 선택할 수 없습니다.");if(rows.get(0).get("triageTier").equals("PENDING"))throw Store.bad("먼저 한글 요약과 1차 분류를 완료해 주세요.");}
  String id=UUID.randomUUID().toString();
  store.jdbc().update("INSERT INTO editorial_runs(id,topic_id,collection_id,status,message,created_at) VALUES(?,?,?,'SCREENING','자료의 관련성·시의성·관심 신호와 원문을 검토합니다.',?)",id,topic,collectionId,Instant.now().toString());
  worker.submit(()->select(id,topic,ids));return Map.of("id",id);
 }
 public Map<String,Object> board(String topic){
  var candidates=candidates(topic);var runs=store.rows("SELECT * FROM editorial_runs WHERE topic_id=? ORDER BY created_at DESC LIMIT 8",topic);
  for(var run:runs){Object selection=run.remove("selectionJson");try{run.put("selection",selection==null?null:json.readTree(selection.toString()));}catch(Exception ignored){}
   if(run.get("analysisId")!=null){var job=analysis.job(run.get("analysisId").toString());run.put("analysis",job);if(Set.of("SUCCESS","FAILED","AUTH_REQUIRED","LIMIT_REACHED","CANCELLED").contains(job.get("status")))run.put("status",job.get("status"));}
  }
  var collections=store.rows("SELECT * FROM runs WHERE topic_id=? ORDER BY started_at DESC LIMIT 1",topic);
  if(!collections.isEmpty())collections.get(0).put("sources",store.rows("SELECT source_name,status,fetched,added,message FROM run_sources WHERE run_id=?",collections.get(0).get("id")));
  var hidden=store.jdbc().query("SELECT article_id FROM topic_articles WHERE topic_id=? AND status='HIDDEN'",(r,n)->r.getString(1),topic);
  return Map.of("settings",settings(topic),"collecting",collection.running(topic),"aiBusy",analysis.runner.isBusy(),"triageBusy",triage.busy(topic),"runs",runs,"candidates",candidates,"modules",collection.modules(),"sources",store.sources(topic),"collections",collections,"hidden",hidden);
 }
 private Set<String> drafted(String topic){return DraftHistory.used(store,json);}
 public List<Map<String,Object>> candidates(String topicId){
  var topic=store.topic(topicId);var used=drafted(topicId);List<Map<String,Object>> ranked=new ArrayList<>();
  var rows=store.rows("SELECT a.*,ta.status FROM articles a JOIN topic_articles ta ON ta.article_id=a.id WHERE ta.topic_id=? AND ta.status<>'HIDDEN' ORDER BY a.published_at DESC NULLS LAST,a.collected_at DESC LIMIT 500",topicId);
  for(var row:rows){if(used.contains(row.get("id").toString()))continue;
   String content=(row.get("title")+" "+row.get("excerpt")).toLowerCase(Locale.ROOT);int fit=0;
   for(String k:topic.keywords())if(matches(content,k))fit++;
   if(topic.exclusions().stream().anyMatch(k->matches(content,k)))continue;
   if(!topic.keywords().isEmpty()&&fit==0)continue;
   long days=60;try{days=Math.max(0,Duration.between(Instant.parse(row.get("publishedAt").toString()),Instant.now()).toDays());}catch(Exception ignored){}
   if(days>30)continue;
   var signals=analysis.signals(row.get("signals"));int points=((Number)signals.getOrDefault("points",0)).intValue(),comments=((Number)signals.getOrDefault("comments",0)).intValue();
   int relevance=topic.keywords().isEmpty()?15:Math.min(30,15+fit*5),freshness=days<=3?25:days<=7?18:days<=14?10:4;
   int evidence=row.get("excerpt").toString().length()>=200?20:5,interest=Math.min(25,(int)(Math.log1p(points+comments*2)*4));
   List<String> reasons=List.of("분야 관련성 "+relevance+"/30","최신성 "+freshness+"/25", "제공 설명 "+evidence+"/20",signals.isEmpty()?"관심 신호 미확인": "HN 추천 "+points+" · 댓글 "+comments+" (관측 시점 기준)");
   var item=new LinkedHashMap<String,Object>();item.put("id",row.get("id"));item.put("title",row.get("title"));item.put("url",row.get("url"));item.put("sourceName",row.get("sourceName"));item.put("score",relevance+freshness+evidence+interest);item.put("reasons",reasons);item.put("signals",signals);item.put("publishedAt",row.get("publishedAt"));ranked.add(item);
  }
  ranked.sort(Comparator.<Map<String,Object>>comparingInt(r->((Number)r.get("score")).intValue()).reversed().thenComparing(r->r.get("id").toString()));return ranked;
 }
 static boolean matches(String text,String keyword){String k=keyword.toLowerCase(Locale.ROOT);return k.matches("[a-z0-9 ]+")?java.util.regex.Pattern.compile("(?<![a-z0-9])"+java.util.regex.Pattern.quote(k)+"(?![a-z0-9])").matcher(text).find():text.contains(k);}
 static boolean recent(Map<String,Object> row){try{return Instant.parse(Objects.toString(row.get("publishedAt"),Objects.toString(row.get("collectedAt"),""))).isAfter(Instant.now().minus(Duration.ofDays(7)));}catch(Exception e){return false;}}
 void select(String id,String topic,List<String> selectedIds){try{
  var used=drafted(topic);
  var ranked=store.rows("SELECT a.id,a.title,a.url,a.published_at,a.collected_at,ta.triage_score score,ta.triage_tier tier FROM articles a JOIN topic_articles ta ON a.id=ta.article_id WHERE ta.topic_id=? AND ta.status<>'HIDDEN' AND ta.triage_tier<>'PENDING' ORDER BY CASE ta.triage_tier WHEN 'T1' THEN 0 WHEN 'T2' THEN 1 ELSE 2 END,ta.triage_score DESC,a.collected_at DESC",topic).stream().filter(r->!used.contains(r.get("id").toString())).filter(r->!selectedIds.isEmpty()||recent(r)).filter(r->selectedIds.isEmpty()?Set.of("T1","T2").contains(r.get("tier")):selectedIds.contains(r.get("id").toString())).toList();
  List<Map<String,Object>> chosen=new ArrayList<>();Map<String,Integer> domains=new HashMap<>();
  for(var row:ranked){String host;try{host=java.net.URI.create(row.get("url").toString()).getHost();}catch(Exception e){continue;}
   if(selectedIds.isEmpty()&&domains.getOrDefault(host,0)>=2)continue;domains.merge(host,1,Integer::sum);chosen.add(row);if(chosen.size()==5)break;
  }
  store.jdbc().update("UPDATE editorial_runs SET selection_json=? WHERE id=?",store.encode(Map.of("eligible",ranked.size(),"selected",chosen,"note","분류된 자료 중 최대 5건의 원문을 확인합니다. 1차 등급은 게재 확정이나 예상 클릭률이 아닙니다.")),id);
  if(chosen.isEmpty()){store.jdbc().update("UPDATE editorial_runs SET status='EMPTY',message='최근 7일 내 미작성 1·2티어 후보가 없습니다. 이전 작성 원문과 오래된 자료는 자동으로 재사용하지 않습니다.',finished_at=? WHERE id=?",Instant.now().toString(),id);return;}
  // Read full public originals before deciding whether the topic has enough evidence.
  List<Map<String,Object>> unavailable=new ArrayList<>();
  for(var candidate:chosen){var rows=store.rows("SELECT * FROM articles WHERE id=?",candidate.get("id"));var row=rows.get(0);
   var original=originals.read(json.valueToTree(row));boolean ok="ORIGINAL_EXTRACT".equals(original.get("coverage"));
   store.jdbc().update("UPDATE articles SET original_status=?,original_message=?,original_checked_at=? WHERE id=?",ok?"AVAILABLE":Objects.toString(original.get("errorCode"),"FAILED"),Objects.toString(original.get("message"),""),Instant.now().toString(),candidate.get("id"));
   if(ok)store.jdbc().update("UPDATE articles SET excerpt=?,coverage='ORIGINAL_EXTRACT' WHERE id=?",original.get("text"),candidate.get("id"));
   else {var failure=new LinkedHashMap<>(candidate);failure.put("reason",Objects.toString(original.get("message"),"원문 미확보"));unavailable.add(failure);}
  }
  chosen.removeIf(c->unavailable.stream().anyMatch(f->f.get("id").equals(c.get("id"))));
  if(chosen.isEmpty()){store.jdbc().update("UPDATE editorial_runs SET status='EMPTY',message='선택 자료의 원문을 확보하지 못해 집중 분석을 시작하지 않았습니다. 자료별 원문 상태를 확인하세요.',selection_json=?,finished_at=? WHERE id=?",store.encode(Map.of("selected",chosen,"unavailable",unavailable,"note","원문 미확보 자료에는 집중 분석 토큰을 사용하지 않았습니다.")),Instant.now().toString(),id);return;}
  String direction="기본 독자: "+settings(topic).get("audience")+". 읽을 이유와 실질적 효용을 중심으로 추천·보류·제외를 판단하세요. 자료가 충분한 소재만 추천하고 근거가 부족하면 보류하세요. 추천 우선순위 순서로 정렬하세요.";
  List<String> pastTitles=new ArrayList<>();for(var past:store.rows("SELECT b.result_json FROM blog_drafts b JOIN analysis_jobs a ON a.id=b.analysis_id WHERE a.topic_id=? AND b.result_json IS NOT NULL AND b.status<>'FAILED' ORDER BY b.created_at DESC LIMIT 3",topic))try{pastTitles.add(AnalysisService.cut(json.readTree(past.get("resultJson").toString()).path("title").asText(),100));}catch(Exception ignored){}
  if(!pastTitles.isEmpty())direction+=" 이미 작성한 제목: "+String.join(" / ",pastTitles)+". 같은 사건·질문의 반복은 제외하고 실질적인 새 정보가 있는 경우만 차이를 설명해 추천하세요.";
  AnalysisModels.Request request;AnalysisModels.Preview preview;
  while(true){request=new AnalysisModels.Request(topic,chosen.stream().map(c->c.get("id").toString()).toList(),"FULL",direction);
   try{preview=analysis.preview(request);break;}catch(org.springframework.web.server.ResponseStatusException e){if(chosen.size()==1||e.getReason()==null||!e.getReason().contains("입력 상한"))throw e;chosen.remove(chosen.size()-1);}
  }
  store.jdbc().update("UPDATE editorial_runs SET selection_json=? WHERE id=?",store.encode(Map.of("eligible",ranked.size(),"selected",chosen,"unavailable",unavailable,"note","1차 분류 후 선택한 최대 5건 중 원문 확보 자료만 집중 분석합니다. 원문을 자르지 않고 총 입력 120,000자를 넘으면 자료 수를 줄입니다. 점수는 예상 클릭률이 아닙니다.")),id);
  var result=analysis.start(new AnalysisModels.Start(request,preview.fingerprint()));
  store.jdbc().update("UPDATE editorial_runs SET status='ANALYZING',analysis_id=?,message='독자의 질문과 원문 근거를 바탕으로 추천 소재를 선정합니다.' WHERE id=?",result.get("jobId"),id);
 }catch(Exception e){store.jdbc().update("UPDATE editorial_runs SET status='FAILED',message=?,finished_at=? WHERE id=?",e instanceof org.springframework.web.server.ResponseStatusException r?r.getReason():"추천 준비에 실패했습니다. 수집 결과와 AI 연결 상태를 확인하고 다시 시도하세요.",Instant.now().toString(),id);}}
}
