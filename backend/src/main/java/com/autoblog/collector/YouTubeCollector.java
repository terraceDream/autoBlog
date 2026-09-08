package com.autoblog.collector;

import com.autoblog.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Component
public class YouTubeCollector implements Collector {
    final SafeHttp http; final ObjectMapper json; final String key;
    public YouTubeCollector(SafeHttp http,ObjectMapper json,@Value("${app.youtube-key}") String key) { this.http=http;this.json=json;this.key=key; }
    public ModuleInfo info() { return new ModuleInfo("YOUTUBE","YouTube","키워드·채널로 최신 영상 정보 수집",!key.isBlank(),"서버의 YOUTUBE_API_KEY 설정 필요"); }
    static String enc(String s) { return URLEncoder.encode(s,StandardCharsets.UTF_8); }
    public List<Candidate> collect(Topic topic,Source source) throws Exception {
        if(key.isBlank()) throw new IllegalArgumentException("YOUTUBE_API_KEY가 설정되지 않았습니다.");
        String query=source.query().isBlank()?String.join("|",topic.keywords()):source.query();
        if(query.isBlank()&&source.channelId().isBlank()) query=topic.name();
        String url="https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&order=date&maxResults=50&key="+enc(key)+"&q="+enc(query)+"&relevanceLanguage="+enc(topic.language())+"&regionCode="+enc(topic.region());
        if(!source.channelId().isBlank()) url+="&channelId="+enc(source.channelId());
        var root=json.readTree(http.get(url,Map.of())); List<Candidate> items=new ArrayList<>();
        for(var e:root.path("items")) { var s=e.path("snippet"); String id=e.path("id").path("videoId").asText(); if(id.isBlank()) continue;
            items.add(new Candidate(RssCollector.clean(s.path("title").asText()),"https://www.youtube.com/watch?v="+id,s.path("channelTitle").asText(),id,RssCollector.clean(s.path("description").asText()),Instant.parse(s.path("publishedAt").asText())));
        }
        return items;
    }
}
