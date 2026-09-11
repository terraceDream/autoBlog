package com.autoblog;

import com.autoblog.collector.SafeHttp;
import com.fasterxml.jackson.databind.*;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.autoblog.DraftModels.*;

@Component
public class CommonsImages {
    public record Candidate(String id,String title,String description,String url,String sourceUrl,String credit,String license,String licenseUrl) {}
    public record Selection(String id,String caption,int afterParagraph) {}
    public record Search(List<Candidate> candidates,String message) {}
    private final SafeHttp http;private final ObjectMapper json;
    public CommonsImages(SafeHttp http,ObjectMapper json){this.http=http;this.json=json;}
    public Search search(JsonNode issue){
        Set<String> queries=new LinkedHashSet<>();
        String topic=issue.toString().toLowerCase(Locale.ROOT);
        if(topic.contains("github actions")||topic.contains("continuous integration")||topic.contains("품질 게이트"))queries.add("continuous integration diagram");
        else if(topic.contains("litellm")||topic.contains("gateway")||topic.contains("게이트웨이"))queries.add("client server model");
        else if(topic.contains("agent")||topic.contains("에이전트"))queries.add("intelligent agent diagram");
        for(var tag:issue.path("tags")){String q=tag.asText().replaceAll("[^\\p{L}\\p{N} -]"," ").trim();if(q.length()>2)queries.add(q);if(queries.size()==2)break;}
        if(queries.isEmpty())queries.add(issue.path("title").asText().replaceAll("[^\\p{L}\\p{N} -]"," "));
        Map<String,Candidate> found=new LinkedHashMap<>();boolean failed=false;
        for(String query:queries)try{
            String url="https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrnamespace=6&gsrlimit=8&prop=imageinfo&iiprop=url%7Cmime%7Csize%7Cextmetadata&iiurlwidth=1200&format=json&gsrsearch="+URLEncoder.encode(AnalysisService.cut(query,100),StandardCharsets.UTF_8);
            JsonNode root=json.readTree(http.get(url,Map.of()));
            for(JsonNode page:root.path("query").path("pages")){var candidate=parse(page);if(candidate!=null&&found.size()<12)found.putIfAbsent(candidate.id(),candidate);}
        }catch(Exception e){failed=true;}
        return new Search(List.copyOf(found.values()),found.isEmpty()?(failed?"공개 이미지 검색에 실패했습니다. 이미지를 수동으로 추가하거나 다시 작성해 주세요.":"사용 조건을 확인할 수 있는 이미지 후보가 없습니다."):"Wikimedia Commons의 공개 라이선스 후보에서 본문에 맞는 이미지를 선택합니다. 실제 관련성과 캡션은 미리보기에서 확인하세요.");
    }
    static Candidate parse(JsonNode page){
        if(page.path("title").asText().toLowerCase(Locale.ROOT).matches(".*[-_](bg|ru|zh|de|es|fr|ja|tr|fa|ar|uk|pl|cs|pt|it|sv|nl|he|el|th|vi)\\.(png|svg|jpg|jpeg|webp)$"))return null;
        var info=page.path("imageinfo").path(0);var meta=info.path("extmetadata");
        String license=plain(meta,"LicenseShortName"),licenseUrl=plain(meta,"LicenseUrl");
        if(!license.matches("CC BY(?:-SA)? (?:[1-4]\\.0|2\\.5)|CC0"))return null;
        if(!licenseUrl.startsWith("https://creativecommons.org/"))return null;
        if(!plain(meta,"Restrictions").isBlank()||!Set.of("image/png","image/jpeg","image/svg+xml","image/webp").contains(info.path("mime").asText())||info.path("width").asInt()<300)return null;
        String url=info.path("thumburl").asText(info.path("url").asText()),source=info.path("descriptionurl").asText(),credit=plain(meta,"Artist");
        try{if(!Set.of("upload.wikimedia.org","thumb.wikimedia.org").contains(URI.create(url).getHost())||!"https".equals(URI.create(url).getScheme())||!"commons.wikimedia.org".equals(URI.create(source).getHost())||!source.startsWith("https://")||credit.isBlank()||credit.length()>300)return null;}catch(Exception e){return null;}
        return new Candidate(page.path("pageid").asText(),page.path("title").asText(),AnalysisService.cut(plain(meta,"ImageDescription"),700),url,source,credit,license,licenseUrl);
    }
    static String plain(JsonNode meta,String field){return Jsoup.parse(meta.path(field).path("value").asText()).text();}
    public static List<Image> resolve(List<Selection> selections,List<Candidate> candidates,String html){
        if(selections==null)return List.of();if(selections.size()>3)throw Store.bad("자동 이미지는 최대 3개입니다.");
        var byId=new HashMap<String,Candidate>();candidates.forEach(c->byId.put(c.id(),c));
        var result=new ArrayList<Image>();var used=new HashSet<String>();int count=Jsoup.parse(html).select("p").size();
        for(var s:selections){if(s==null||!used.add(s.id())||!byId.containsKey(s.id())||s.caption()==null||s.caption().isBlank()||s.caption().length()>500||s.afterParagraph()<1||s.afterParagraph()>count)throw Store.bad("자동 이미지 선택 또는 문단 위치가 유효하지 않습니다.");
            var c=byId.get(s.id());result.add(new Image(c.url(),c.sourceUrl(),c.credit(),c.license(),c.licenseUrl(),s.caption(),s.afterParagraph(),true));}
        return result;
    }
}
