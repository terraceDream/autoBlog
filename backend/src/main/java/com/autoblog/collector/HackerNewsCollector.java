package com.autoblog.collector;
import com.autoblog.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component
public class HackerNewsCollector implements Collector {
 private final SafeHttp http;private final ObjectMapper json;
 public HackerNewsCollector(SafeHttp http,ObjectMapper json){this.http=http;this.json=json;}
 public ModuleInfo info(){return new ModuleInfo("HN_SEARCH","기술 커뮤니티 원문 발견","키워드로 외부 원문과 Hacker News 추천·댓글 신호 수집",true,"API 키 없이 이용");}
 public List<Candidate> collect(Topic topic,Source source)throws Exception{
  String query=source.query().isBlank()?topic.name():source.query();
  String url="https://hn.algolia.com/api/v1/search?tags=story&hitsPerPage=50&query="+URLEncoder.encode(query,StandardCharsets.UTF_8)+"&numericFilters=created_at_i%3E"+Instant.now().minus(30,ChronoUnit.DAYS).getEpochSecond();
  var data=json.readTree(http.get(url,Map.of()));List<Candidate> items=new ArrayList<>();
  for(var hit:data.path("hits")){
   String link=hit.path("url").asText(),id=hit.path("objectID").asText();
   if(!link.startsWith("https://")&&!link.startsWith("http://"))continue;
   var signals=Map.<String,Object>of("provider","Hacker News","points",Math.max(0,hit.path("points").asInt()),"comments",Math.max(0,hit.path("num_comments").asInt()),"discussionUrl","https://news.ycombinator.com/item?id="+id,"observedAt",Instant.now().toString());
   items.add(new Candidate(hit.path("title").asText(),link,"HN 제출자: "+hit.path("author").asText(),id,RssCollector.clean(hit.path("story_text").asText("")),Instant.ofEpochSecond(hit.path("created_at_i").asLong()),signals));
  }
  return items;
 }
}
