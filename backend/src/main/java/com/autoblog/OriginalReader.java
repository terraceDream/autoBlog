package com.autoblog;

import com.autoblog.collector.SafeHttp;
import com.fasterxml.jackson.databind.JsonNode;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class OriginalReader {
    private final SafeHttp http;
    public OriginalReader(SafeHttp http){this.http=http;}
    public Map<String,Object> read(JsonNode source) {
        String url=source.path("url").asText(),text=source.path("excerpt").asText();
        String coverage="EXCERPT",message="저장된 발췌문만 참고했습니다.";
        try {
            String host=java.net.URI.create(url).getHost();
            if(host==null||host.equals("youtu.be")||host.equals("youtube.com")||host.endsWith(".youtube.com"))throw new IllegalArgumentException();
            var document=Jsoup.parse(new java.io.ByteArrayInputStream(http.get(url,Map.of())),null,url);
            document.select("script,style,nav,header,footer,aside,form,noscript,iframe").remove();
            var article=document.selectFirst("article");if(article==null)article=document.selectFirst("main");
            if(article==null)throw new IllegalArgumentException();
            String extracted=article.text();if(extracted.length()<200)throw new IllegalArgumentException();
            text=AnalysisService.cut(extracted,8000);coverage="ORIGINAL_EXTRACT";
            message=extracted.length()>8000?"원문 추출 내용 중 앞 8,000자를 참고합니다.":"공개 원문에서 본문을 추출했습니다. 추출 누락 가능성이 있습니다.";
        }catch(Exception ignored){}
        return Map.of("id",source.path("id").asText(),"title",source.path("title").asText(),"url",url,"text",text,"coverage",coverage,"message",message);
    }
}
