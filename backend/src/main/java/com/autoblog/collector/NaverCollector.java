package com.autoblog.collector;

import com.autoblog.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class NaverCollector implements Collector {
    final String type,id,secret; final SafeHttp http; final ObjectMapper json;
    NaverCollector(String type,SafeHttp http,ObjectMapper json,String id,String secret) { this.type=type;this.http=http;this.json=json;this.id=id;this.secret=secret; }
    public ModuleInfo info() { return new ModuleInfo(type,type.equals("NAVER_NEWS")?"네이버 뉴스 검색":"네이버 블로그 검색","검색 결과의 제목·설명·원문 링크 수집",!id.isBlank()&&!secret.isBlank(),"서버의 NAVER_CLIENT_ID / NAVER_CLIENT_SECRET 설정 필요"); }
    public List<Candidate> collect(Topic topic,Source source) throws Exception {
        if(!info().ready()) throw new IllegalArgumentException("네이버 검색 API 인증 정보가 설정되지 않았습니다.");
        boolean news=type.equals("NAVER_NEWS");
        List<String> queries=source.query().isBlank()?(topic.keywords().isEmpty()?List.of(topic.name()):topic.keywords()):List.of(source.query());
        if(queries.size()>5) throw new IllegalArgumentException("검색어가 5개를 초과합니다. 출처의 검색어를 지정해 주세요.");
        Map<String,Candidate> unique=new LinkedHashMap<>();
        for(String q:queries) {
            String url="https://openapi.naver.com/v1/search/"+(news?"news":"blog")+".json?display=100&sort=date&query="+YouTubeCollector.enc(q);
            var root=json.readTree(http.get(url,Map.of("X-Naver-Client-Id",id,"X-Naver-Client-Secret",secret)));
            for(var e:root.path("items")) {
                String link=news?e.path("originallink").asText():e.path("link").asText(); if(link.isBlank()) link=e.path("link").asText();
                Instant date=null;
                try { date=news?ZonedDateTime.parse(e.path("pubDate").asText(),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant():LocalDate.parse(e.path("postdate").asText(),DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(); } catch(Exception ignored) {}
                unique.put(link,new Candidate(RssCollector.clean(e.path("title").asText()),link,RssCollector.clean(e.path("bloggername").asText()),link,RssCollector.clean(e.path("description").asText()),date));
            }
        }
        return new ArrayList<>(unique.values());
    }
    @Configuration
    public static class Config {
        @Bean Collector naverNews(SafeHttp h,ObjectMapper j,@Value("${app.naver-client-id}") String id,@Value("${app.naver-client-secret}") String secret) { return new NaverCollector("NAVER_NEWS",h,j,id,secret); }
        @Bean Collector naverBlog(SafeHttp h,ObjectMapper j,@Value("${app.naver-client-id}") String id,@Value("${app.naver-client-secret}") String secret) { return new NaverCollector("NAVER_BLOG",h,j,id,secret); }
    }
}
