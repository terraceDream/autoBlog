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
 public EditorialWorkbench(Store s,SourceDiscovery d,CollectionService c,AnalysisService a,OriginalReader o,ObjectMapper j){store=s;discovery=d;collection=c;analysis=a;originals=o;json=j;}
 @PostConstruct void recover(){store.jdbc().update("UPDATE editorial_runs SET status='FAILED',message='서버 재시작으로 선별이 중단됐습니다. 다시 실행할 수 있습니다.' WHERE status='SCREENING'");}
 @PreDestroy void stop(){worker.shutdownNow();}
 public Map<String,Object> settings(String topic){store.topic(topic);var rows=store.rows("SELECT * FROM editorial_settings WHERE topic_id=?",topic);return rows.isEmpty()?Map.of("audience","AI를 업무에 활용하려는 실무자","automatic",false):rows.get(0);}
 public synchronized Object settings(String topic,String audience,boolean automatic){store.topic(topic);if(audience==null||audience.isBlank()||audience.length()>300)throw Store.bad("독자를 1~300자로 입력해 주세요.");
  if(store.jdbc().update("UPDATE editorial_settings SET audience=?,automatic=? WHERE topic_id=?",audience,automatic,topic)==0)store.jdbc().update("INSERT INTO editorial_settings(topic_id,audience,automatic) VALUES(?,?,?)",topic,audience,automatic);
  return settings(topic);
 }
 public synchronized Map<String,Object> start(String topic){
  if(busy(topic))throw Store.bad("이미 수집 또는 추천 작업이 진행 중입니다.");
  var prefs=settings(topic);settings(topic,prefs.get("audience").toString(),true);
  return Map.of("runId",collection.start(topic,"EDITORIAL"));
 }
 boolean busy(String topic){return collection.running(topic)||analysis.running(topic)||analysis.runner.isBusy()||store.jdbc().queryForObject("SELECT COUNT(*) FROM editorial_runs WHERE topic_id=? AND status='SCREENING'",Integer.class,topic)>0;}
 @EventListener public void collected(CollectionService.Finished event){if(discovery.automatic(event.topicId()))try{screen(event.topicId(),event.runId());}catch(org.springframework.web.server.ResponseStatusException e){store.jdbc().update("INSERT INTO editorial_runs(id,topic_id,collection_id,status,message,created_at,finished_at) VALUES(?,?,?,'FAILED',?,?,?)",UUID.randomUUID().toString(),event.topicId(),event.runId(),"다른 AI 작업이 진행 중이어서 자동 추천을 시작하지 않았습니다. 완료 후 수집된 자료만 다시 선별할 수 있습니다.",Instant.now().toString(),Instant.now().toString());}}
 public synchronized Map<String,Object> screen(String topic,String collectionId){
  store.topic(topic);if(busy(topic))throw Store.bad("이미 수집 또는 추천 작업이 진행 중입니다.");
  String id=UUID.randomUUID().toString();
  store.jdbc().update("INSERT INTO editorial_runs(id,topic_id,collection_id,status,message,created_at) VALUES(?,?,?,'SCREENING','자료의 관련성·시의성·관심 신호와 원문을 검토합니다.',?)",id,topic,collectionId,Instant.now().toString());
  worker.submit(()->select(id,topic));return Map.of("id",id);
 }
 public Map<String,Object> board(String topic){
  var candidates=candidates(topic);var runs=store.rows("SELECT * FROM editorial_runs WHERE topic_id=? ORDER BY created_at DESC LIMIT 8",topic);
  for(var run:runs){Object selection=run.remove("selectionJson");try{run.put("selection",selection==null?null:json.readTree(selection.toString()));}catch(Exception ignored){}
   if(run.get("analysisId")!=null){var job=analysis.job(run.get("analysisId").toString());run.put("analysis",job);if(Set.of("SUCCESS","FAILED","AUTH_REQUIRED","LIMIT_REACHED","CANCELLED").contains(job.get("status")))run.put("status",job.get("status"));}
  }
  var collections=store.rows("SELECT * FROM runs WHERE topic_id=? ORDER BY started_at DESC LIMIT 1",topic);
  if(!collections.isEmpty())collections.get(0).put("sources",store.rows("SELECT source_name,status,fetched,added,message FROM run_sources WHERE run_id=?",collections.get(0).get("id")));
  var hidden=store.jdbc().query("SELECT article_id FROM topic_articles WHERE topic_id=? AND status='HIDDEN'",(r,n)->r.getString(1),topic);
  return Map.of("settings",settings(topic),"collecting",collection.running(topic),"aiBusy",analysis.runner.isBusy(),"runs",runs,"candidates",candidates,"modules",collection.modules(),"sources",store.sources(topic),"collections",collections,"hidden",hidden);
 }
 private Set<String> drafted(String topic){
  Set<String> ids=new HashSet<>();
  for(var row:store.rows("SELECT b.issue_index,a.result_json FROM blog_drafts b JOIN analysis_jobs a ON a.id=b.analysis_id WHERE a.topic_id=? AND b.status<>'FAILED'",topic))try{
   var issue=json.readTree(row.get("resultJson").toString()).path("issues").path(((Number)row.get("issueIndex")).intValue());issue.path("sourceIds").forEach(v->ids.add(v.asText()));
  }catch(Exception ignored){}return ids;
 }
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
 void select(String id,String topic){try{
  var ranked=candidates(topic);List<Map<String,Object>> chosen=new ArrayList<>();Map<String,Integer> domains=new HashMap<>();
  for(var row:ranked){String host;try{host=java.net.URI.create(row.get("url").toString()).getHost();}catch(Exception e){continue;}
   if(domains.getOrDefault(host,0)>=3)continue;domains.merge(host,1,Integer::sum);chosen.add(row);if(chosen.size()==10)break;
  }
  store.jdbc().update("UPDATE editorial_runs SET selection_json=? WHERE id=?",store.encode(Map.of("eligible",ranked.size(),"scanLimit",500,"days",30,"selected",chosen,"note","최근 30일 자료 중 관련 후보를 최대 10건, 도메인당 최대 3건 검토합니다. 점수는 편집 우선순위이며 예상 클릭률이 아닙니다.")),id);
  if(chosen.isEmpty()){store.jdbc().update("UPDATE editorial_runs SET status='EMPTY',message='새로 추천할 관련 자료가 없습니다. 최근 30일 범위와 이미 작성한 소재 제외 조건을 확인하세요.',finished_at=? WHERE id=?",Instant.now().toString(),id);return;}
  // Read full public originals before deciding whether the topic has enough evidence.
  for(var candidate:chosen){var rows=store.rows("SELECT * FROM articles WHERE id=?",candidate.get("id"));var row=rows.get(0);
   var original=originals.read(json.valueToTree(row));if("ORIGINAL_EXTRACT".equals(original.get("coverage")))store.jdbc().update("UPDATE articles SET excerpt=?,coverage='ORIGINAL_EXTRACT' WHERE id=?",original.get("text"),candidate.get("id"));
  }
  String direction="기본 독자: "+settings(topic).get("audience")+". 읽을 이유와 실질적 효용을 중심으로 추천·보류·제외를 판단하세요. 자료가 충분한 소재만 추천하고 근거가 부족하면 보류하세요. 추천 우선순위 순서로 정렬하세요.";
  List<String> pastTitles=new ArrayList<>();for(var past:store.rows("SELECT b.result_json FROM blog_drafts b JOIN analysis_jobs a ON a.id=b.analysis_id WHERE a.topic_id=? AND b.result_json IS NOT NULL AND b.status<>'FAILED' ORDER BY b.created_at DESC LIMIT 3",topic))try{pastTitles.add(AnalysisService.cut(json.readTree(past.get("resultJson").toString()).path("title").asText(),100));}catch(Exception ignored){}
  if(!pastTitles.isEmpty())direction+=" 이미 작성한 제목: "+String.join(" / ",pastTitles)+". 같은 사건·질문의 반복은 제외하고 실질적인 새 정보가 있는 경우만 차이를 설명해 추천하세요.";
  AnalysisModels.Request request;AnalysisModels.Preview preview;
  while(true){request=new AnalysisModels.Request(topic,chosen.stream().map(c->c.get("id").toString()).toList(),"FULL",direction);
   try{preview=analysis.preview(request);break;}catch(org.springframework.web.server.ResponseStatusException e){if(chosen.size()==1||e.getReason()==null||!e.getReason().contains("입력 상한"))throw e;chosen.remove(chosen.size()-1);}
  }
  store.jdbc().update("UPDATE editorial_runs SET selection_json=? WHERE id=?",store.encode(Map.of("eligible",ranked.size(),"scanLimit",500,"days",30,"selected",chosen,"note","후보 최대 10건, 도메인당 최대 3건. 확보한 원문은 자르지 않고 전달하며 총 입력 120,000자를 넘으면 검토할 자료 수를 줄입니다. 점수는 예상 클릭률이 아닙니다.")),id);
  var result=analysis.start(new AnalysisModels.Start(request,preview.fingerprint()));
  store.jdbc().update("UPDATE editorial_runs SET status='ANALYZING',analysis_id=?,message='독자의 질문과 원문 근거를 바탕으로 추천 소재를 선정합니다.' WHERE id=?",result.get("jobId"),id);
 }catch(Exception e){store.jdbc().update("UPDATE editorial_runs SET status='FAILED',message=?,finished_at=? WHERE id=?",e instanceof org.springframework.web.server.ResponseStatusException r?r.getReason():"추천 준비에 실패했습니다. 수집 결과와 AI 연결 상태를 확인하고 다시 시도하세요.",Instant.now().toString(),id);}}
}
