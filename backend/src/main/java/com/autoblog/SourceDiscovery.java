package com.autoblog;
import com.autoblog.collector.Collector;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
public class SourceDiscovery {
 private final Store store;private final List<Collector> collectors;
 public SourceDiscovery(Store store,List<Collector> collectors){this.store=store;this.collectors=collectors;}
 public boolean automatic(String topic){return Boolean.TRUE.equals(store.jdbc().query("SELECT automatic FROM editorial_settings WHERE topic_id=?",r->r.next()?r.getBoolean(1):false,topic));}
 public synchronized List<String> configure(String topicId){
  var topic=store.topic(topicId);List<String> added=new ArrayList<>();
  Set<String> existingQueries=new HashSet<>();
  for(var source:store.sources(topicId))if(source.type().equals("HN_SEARCH")&&source.name().startsWith("자동 발견 · ")){
   String normalized=searchQuery(source.query());
   if(!existingQueries.add(normalized))store.jdbc().update("UPDATE sources SET enabled=FALSE WHERE id=?",source.id());
   else if(!source.query().equals(normalized))store.jdbc().update("UPDATE sources SET query_text=? WHERE id=?",normalized,source.id());
  }
  // A few distinct queries discover new publishers each run; no website URL is needed.
  var queries=new LinkedHashSet<String>();
  topic.keywords().stream().map(SourceDiscovery::searchQuery).filter(k->!k.isBlank()).distinct().limit(3).forEach(queries::add);
  if(queries.isEmpty())queries.add(searchQuery(topic.name()));
  for(String query:queries)add(topicId,"HN_SEARCH","OTHER",query,"자동 발견 · "+query,added);
  for(var collector:collectors){var info=collector.info();if(info.ready()&&Set.of("YOUTUBE","NAVER_NEWS","NAVER_BLOG").contains(info.type()))
   add(topicId,info.type(),info.type().equals("YOUTUBE")?"VIDEO":info.type().equals("NAVER_NEWS")?"NEWS":"BLOG","", "자동 검색 · "+info.name(),added);
  }
  return added;
 }
 static String searchQuery(String value){return value.toLowerCase(Locale.ROOT).replace("인공지능","artificial intelligence").replace("에이전트","agent").replace("개발","development").replace("모델","model").replace("자동화","automation").replaceAll("\\s+"," ").trim();}
 private void add(String topic,String type,String media,String query,String name,List<String> added){
  boolean exists=store.sources(topic).stream().anyMatch(s->s.type().equals(type)&&searchQuery(s.query()).equals(searchQuery(query))&&s.channelId().isBlank());
  if(exists)return;
  store.jdbc().update("INSERT INTO sources(id,topic_id,name,type,media,url,query_text,channel_id,enabled,created_at) VALUES(?,?,?,?,?,'',?,'',TRUE,?)",UUID.randomUUID().toString(),topic,name,type,media,query,Instant.now().toString());added.add(name);
 }
}
