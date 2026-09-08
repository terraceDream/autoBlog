package com.autoblog.collector;

import com.autoblog.Models.*;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.*;

@Component
public class RssCollector implements Collector {
    private final SafeHttp http;
    public RssCollector(SafeHttp http) { this.http=http; }
    public ModuleInfo info() { return new ModuleInfo("RSS","RSS · Atom","기사·블로그의 공개 피드",true,""); }
    public List<Candidate> collect(Topic topic, Source source) throws Exception {
        return parse(http.get(source.url(),Map.of()),source.url());
    }
    public List<Candidate> parse(byte[] bytes,String base) throws Exception {
        // ROME's default disallows DOCTYPE declarations, preventing external entity expansion.
        var input=new SyndFeedInput(); input.setAllowDoctypes(false);
        try(var reader=new XmlReader(new ByteArrayInputStream(bytes))) {
            var feed=input.build(reader); List<Candidate> out=new ArrayList<>();
            for(var e:feed.getEntries().stream().limit(200).toList()) {
                if(e.getLink()==null||e.getTitle()==null) continue;
                String link;
                try { link=URI.create(base).resolve(e.getLink()).toString(); if(!Set.of("http","https").contains(URI.create(link).getScheme())) continue; } catch(Exception ignored) { continue; }
                var date=e.getPublishedDate()!=null?e.getPublishedDate():e.getUpdatedDate();
                String excerpt=e.getDescription()==null?"":e.getDescription().getValue();
                out.add(new Candidate(clean(e.getTitle()),link,clean(e.getAuthor()),Objects.toString(e.getUri(),link),clean(excerpt),date==null?null:date.toInstant()));
            }
            return out;
        }
    }
    public static String clean(String text) { return text==null?"":Jsoup.parse(text).text(); }
}
