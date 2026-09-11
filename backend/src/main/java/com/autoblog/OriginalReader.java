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
        List<Map<String,String>> images=new ArrayList<>();
        String coverage="EXCERPT",message="원문 본문을 추출하지 못했습니다. 저장된 발췌문만 있어 글 작성에 사용하지 않습니다.";
        try {
            String host=java.net.URI.create(url).getHost();
            if(host==null||host.equals("youtu.be")||host.equals("youtube.com")||host.endsWith(".youtube.com"))throw new IllegalArgumentException();
            var document=Jsoup.parse(new java.io.ByteArrayInputStream(http.get(url,Map.of())),null,url);
            document.select("script,style,nav,header,footer,aside,form,noscript,iframe").remove();
            var article=document.selectFirst("article");if(article==null)article=document.selectFirst("main");
            if(article==null)throw new IllegalArgumentException();
            for(var img:article.select("img")) {
                String imageUrl=img.absUrl("src");if(imageUrl.isBlank())imageUrl=img.absUrl("data-src");
                if(imageUrl.startsWith("https://")&&images.size()<6)images.add(Map.of("url",imageUrl,"caption",img.attr("alt"),"sourceUrl",url));
            }
            String extracted=article.text();if(extracted.length()<200)throw new IllegalArgumentException();
            text=extracted;coverage="ORIGINAL_EXTRACT";
            message="공개 페이지에서 추출한 본문 전체 "+extracted.length()+"자를 전달합니다. 동적 로딩·별도 페이지 내용은 포함되지 않을 수 있습니다.";
        }catch(Exception ignored){}
        return Map.of("id",source.path("id").asText(),"title",source.path("title").asText(),"url",url,"text",text,"coverage",coverage,"message",message,"imageCandidates",images);
    }
}
