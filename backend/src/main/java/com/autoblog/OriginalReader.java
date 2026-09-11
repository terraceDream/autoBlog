package com.autoblog;

import com.autoblog.collector.SafeHttp;
import com.fasterxml.jackson.databind.JsonNode;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class OriginalReader {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(OriginalReader.class);
    private final SafeHttp http;
    public OriginalReader(SafeHttp http){this.http=http;}
    public Map<String,Object> read(JsonNode source) {
        String url=source.path("url").asText(),text=source.path("excerpt").asText();
        List<Map<String,String>> images=new ArrayList<>();
        String coverage="EXCERPT",message="원문 본문을 추출하지 못했습니다. 저장된 발췌문만 있어 글 작성에 사용하지 않습니다.";
        String errorCode="",stage="ADDRESS";
        try {
            String host=java.net.URI.create(url).getHost();
            if(host==null)throw new IllegalArgumentException();
            if(host.equalsIgnoreCase("youtu.be")||host.equalsIgnoreCase("youtube.com")||host.toLowerCase(Locale.ROOT).endsWith(".youtube.com")){
                stage="UNSUPPORTED_VIDEO";throw new IllegalArgumentException();
            }
            stage="FETCH";
            var document=Jsoup.parse(new java.io.ByteArrayInputStream(http.get(url,Map.of())),null,url);
            stage="BODY_NOT_FOUND";
            document.select("script,style,nav,header,footer,aside,form,noscript,iframe").remove();
            org.jsoup.nodes.Element article=null;
            // Prefer explicit post bodies over layout containers or the first related-story card.
            for(String selector:List.of("[itemprop=articleBody], .blog-post-content, .entry-content, .post-content, .article-body", "article", "main, [role=main]")){
                article=document.select(selector).stream().filter(e->e.text().length()>=200)
                    .max(Comparator.comparingInt(e->e.text().length())).orElse(null);
                if(article!=null)break;
            }
            if(article==null)throw new IllegalArgumentException();
            for(var img:article.select("img")) {
                String imageUrl=img.absUrl("src");if(imageUrl.isBlank())imageUrl=img.absUrl("data-src");
                if(imageUrl.startsWith("https://")&&images.size()<6)images.add(Map.of("url",imageUrl,"caption",img.attr("alt"),"sourceUrl",url));
            }
            String extracted=article.text();if(extracted.length()<200)throw new IllegalArgumentException();
            text=extracted;coverage="ORIGINAL_EXTRACT";
            message="공개 페이지에서 추출한 본문 전체 "+extracted.length()+"자를 전달합니다. 동적 로딩·별도 페이지 내용은 포함되지 않을 수 있습니다.";
        }catch(Exception failure){
            errorCode=stage;
            String reason=switch(stage){
                case "ADDRESS" -> "원문 주소 형식이 올바르지 않습니다.";
                case "UNSUPPORTED_VIDEO" -> "유튜브 영상은 현재 원문 자막 수집을 지원하지 않습니다.";
                case "BODY_NOT_FOUND" -> "페이지를 받았지만 200자 이상의 본문 영역을 찾지 못했습니다. 페이지 구조 또는 동적 로딩을 확인해야 합니다.";
                default -> "원문 서버 연결 또는 응답 읽기에 실패했습니다.";
            };
            if(stage.equals("FETCH")){
                var status=java.util.regex.Pattern.compile("^외부 서버 응답 HTTP (\\d{3})$").matcher(Objects.toString(failure.getMessage(),""));
                if(status.matches()){errorCode="HTTP_"+status.group(1);reason="원문 서버가 HTTP "+status.group(1)+" 응답을 반환했습니다.";}
                else if(failure instanceof java.net.http.HttpTimeoutException){errorCode="TIMEOUT";reason="원문 서버 응답 대기 시간이 초과되었습니다.";}
                else if(failure instanceof IllegalArgumentException){errorCode="ADDRESS_OR_DNS";reason="공개 HTTP(S) 주소와 DNS 확인에 실패했습니다.";}
            }
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            message=reason+" 저장된 발췌문으로 대체 작성하지 않았습니다.";
            // Do not log URLs, query credentials, or arbitrary upstream exception messages.
            log.warn("Original extraction failed: code={}, exception={}",errorCode,failure.getClass().getSimpleName());
        }
        return Map.of("id",source.path("id").asText(),"title",source.path("title").asText(),"url",url,"text",text,"coverage",coverage,"message",message,"imageCandidates",images,"errorCode",errorCode);
    }
}
