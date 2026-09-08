package com.autoblog;

import com.autoblog.collector.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class CollectorParsingTest {
    @Test void parsesAtomAndRejectsExternalEntities() throws Exception {
        RssCollector parser=new RssCollector(null);
        String atom="<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>Feed</title><id>urn:feed</id><updated>2026-09-08T00:00:00Z</updated><entry><title>Entry</title><id>urn:1</id><updated>2026-09-08T00:00:00Z</updated><link href=\"/article\"/><summary>Text</summary></entry></feed>";
        var items=parser.parse(atom.getBytes(StandardCharsets.UTF_8),"https://example.com/feed");assertThat(items).hasSize(1);assertThat(items.get(0).url()).isEqualTo("https://example.com/article");
        String xxe="<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><rss version=\"2.0\"><channel><title>&x;</title></channel></rss>";
        assertThatThrownBy(()->parser.parse(xxe.getBytes(StandardCharsets.UTF_8),"https://example.com/feed")).isInstanceOf(Exception.class);
    }
    @Test void canonicalizesWithoutDestroyingMeaningfulQuery() {
        assertThat(CollectionService.canonical("https://EXAMPLE.com:443/a?utm_source=x&b=2&a=1#part")).isEqualTo("https://example.com/a?a=1&b=2");
        assertThat(CollectionService.canonical("javascript:alert(1)")).isNull();
        assertThat(CollectionService.canonical("https://example.com/a?x=%2F")).isEqualTo("https://example.com/a?x=%2F");
    }
    @Test void rejectsPrivateDestinationsAndBadSchedules() {
        for(String url:new String[]{"http://127.0.0.1/","http://169.254.169.254/latest/","http://[::1]/","http://10.0.0.1/","file:///etc/passwd","https://user:pass@example.com/","http://100.64.0.1/"}) assertThatThrownBy(()->SafeHttp.validate(url)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->CollectionService.nextRun("* * * * * *","Asia/Seoul")).isInstanceOf(Exception.class);
        assertThatThrownBy(()->CollectionService.nextRun("0 0 9 * * *","Not/AZone")).isInstanceOf(Exception.class);
    }
}
