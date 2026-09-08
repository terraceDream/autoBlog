package com.autoblog.collector;

import com.autoblog.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SearchCollectorsTest {
    Topic topic() {return new Topic("t","개발","","",List.of("AI","코딩"),List.of(),List.of(),"ko","KR",true,false,"0 0 9 * * *","Asia/Seoul",null,"","",0,0);}
    Source source(String type) {return new Source("s","t","검색",type,"NEWS","","","",true,null);}
    @Test void youtubeParsesMetadataWithoutDownloadingVideoOrCaptions() throws Exception {
        SafeHttp http=mock(SafeHttp.class);
        when(http.get(anyString(),anyMap())).thenReturn("""
            {"nextPageToken":"more","items":[{"id":{"videoId":"abc123"},"snippet":{"title":"AI &amp; tools","description":"New tools","channelTitle":"Dev channel","publishedAt":"2026-09-08T00:00:00Z"}}]}
            """.getBytes(StandardCharsets.UTF_8));
        var collector=new YouTubeCollector(http,new ObjectMapper(),"test-key");var items=collector.collect(topic(),source("YOUTUBE"));
        assertThat(items).hasSize(1);assertThat(items.get(0).title()).isEqualTo("AI & tools");assertThat(items.get(0).url()).isEqualTo("https://www.youtube.com/watch?v=abc123");
        verify(http).get(contains("/youtube/v3/search?"),eq(Map.of()));
    }
    @Test void naverParsesBothFormatsAndDeduplicatesAcrossQueries() throws Exception {
        SafeHttp http=mock(SafeHttp.class);
        when(http.get(contains("news.json"),anyMap())).thenReturn("""
            {"items":[{"title":"<b>AI</b> 뉴스","originallink":"https://example.com/story","link":"https://news.naver.com/story","description":"설명 &amp; 내용","pubDate":"Tue, 08 Sep 2026 09:00:00 +0900"}]}
            """.getBytes(StandardCharsets.UTF_8));
        when(http.get(contains("blog.json"),anyMap())).thenReturn("""
            {"items":[{"title":"AI 블로그","link":"https://example.com/blog","description":"리뷰","bloggername":"작성자","postdate":"20260908"}]}
            """.getBytes(StandardCharsets.UTF_8));
        var news=new NaverCollector("NAVER_NEWS",http,new ObjectMapper(),"test-id","test-secret").collect(topic(),source("NAVER_NEWS"));
        assertThat(news).hasSize(1);assertThat(news.get(0).title()).isEqualTo("AI 뉴스");assertThat(news.get(0).url()).isEqualTo("https://example.com/story");assertThat(news.get(0).publishedAt().toString()).isEqualTo("2026-09-08T00:00:00Z");
        var blog=new NaverCollector("NAVER_BLOG",http,new ObjectMapper(),"test-id","test-secret").collect(topic(),source("NAVER_BLOG"));
        assertThat(blog).hasSize(1);assertThat(blog.get(0).author()).isEqualTo("작성자");assertThat(blog.get(0).publishedAt().toString()).isEqualTo("2026-09-07T15:00:00Z");
    }
}
