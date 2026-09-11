package com.autoblog;

import org.junit.jupiter.api.Test;
import org.jsoup.Jsoup;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static com.autoblog.DraftModels.*;

class DraftImagesTest {
    Image image(String url,int position){return new Image(url,"https://example.com/source","작가 <script>","CC BY 4.0","https://creativecommons.org/licenses/by/4.0/","설명 <img onerror=x>",position,true);}
    @Test void rendersImagesWithEscapedCreditAndStablePlacement(){
        var content=new Content("제목","<p>첫 문단</p><p>둘째 문단</p>","기술",List.of(),List.of(),List.of(image("https://example.com/1.jpg",1),image("https://example.com/2.jpg",1)));
        var doc=Jsoup.parse(DraftImages.render(content));
        assertThat(doc.select("script,[onerror]")).isEmpty();
        assertThat(doc.select("img").eachAttr("src")).containsExactly("https://example.com/1.jpg","https://example.com/2.jpg");
        assertThat(doc.selectFirst("p").nextElementSibling().tagName()).isEqualTo("figure");
        assertThat(doc.selectFirst("figcaption").text()).contains("작가 <script>","CC BY 4.0");
        assertThat(doc.select("figcaption a")).hasSize(4);
    }
    @Test void rejectsUnsafeImageLocations(){
        for(String url:List.of("javascript:alert(1)","file:///secret","http://example.com/a.jpg","https://127.0.0.1/a.jpg"))
            assertThatThrownBy(()->DraftImages.validate(List.of(image(url,1)))).isInstanceOf(RuntimeException.class);
    }
    @Test void oldDraftWithoutImagesStillRenders(){
        assertThat(DraftImages.render(new Content("제목","<p>기존 글</p>","기술",List.of(),List.of(),null))).contains("기존 글").doesNotContain("img");
    }
    @Test void tableStylesAreInThePayloadNotOnlyPreviewCss(){
        var doc=Jsoup.parse(DraftImages.render(new Content("제목","<table><tr><th>항목</th><th>값</th></tr><tr><td>A</td><td>B</td></tr></table>","기술",List.of(),List.of())));
        assertThat(doc.selectFirst("table").attr("border")).isEqualTo("1");
        assertThat(doc.selectFirst("td").attr("style")).contains("border:","padding:12px");
        assertThat(doc.selectFirst("th").attr("style")).contains("background-color:");
        assertThat(doc.select("tr")).hasSize(2);
    }
}
