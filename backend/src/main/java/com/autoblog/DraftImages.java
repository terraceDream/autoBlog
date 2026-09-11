package com.autoblog;

import com.autoblog.collector.SafeHttp;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import java.util.*;
import static com.autoblog.DraftModels.*;

/** Images are explicitly supplied by the editor, never invented by the language model. */
public final class DraftImages {
    private DraftImages() {}
    static void validate(List<Image> images) {
        for (Image image : images) {
            for (String url : List.of(image.url(), image.sourceUrl(), image.licenseUrl())) {
                if (!url.startsWith("https://")) throw Store.bad("이미지와 출처·사용 조건 주소는 공개 HTTPS 주소를 입력해 주세요.");
                SafeHttp.validate(url);
            }
            if (!image.rightsConfirmed()) throw Store.bad("이미지의 블로그 재사용 및 외부 삽입 조건을 확인해 주세요.");
        }
    }
    static String render(Content content) {
        var doc = Jsoup.parseBodyFragment(content.html());
        // Inline formatting survives Tistory's editor; preview-only CSS does not.
        for(var table:doc.select("table")){
            table.attr("style","border-collapse:collapse;width:100%;margin:24px 0;border:1px solid #aebdb7;table-layout:auto")
                .attr("border","1").attr("cellpadding","10").attr("cellspacing","0").attr("data-ke-align","alignLeft");
            for(var cell:table.select("th,td"))cell.attr("style","border:1px solid #aebdb7;padding:12px;text-align:left;vertical-align:top;line-height:1.6;word-break:break-word"+(cell.tagName().equals("th")?";background-color:#eaf2ee;font-weight:bold":""));
        }
        var paragraphs = doc.select("p");
        var images = content.images() == null ? List.<Image>of() : content.images();
        // Reverse insertion preserves the editor's ordering when positions are equal.
        for (int i = images.size()-1; i >= 0; i--) {
            var image = images.get(i);
            var figure = new Element("figure");
            figure.appendElement("img").attr("src",image.url()).attr("alt",image.caption())
                .attr("referrerpolicy","no-referrer").attr("style","max-width:100%;height:auto");
            var caption = figure.appendElement("figcaption").text(image.caption()+" — "+image.credit()+" · ");
            caption.appendElement("a").attr("href",image.sourceUrl()).attr("rel","noopener noreferrer").text("이미지 출처");
            caption.appendText(" · ");
            caption.appendElement("a").attr("href",image.licenseUrl()).attr("rel","noopener noreferrer").text(image.license());
            if (image.afterParagraph()==0 || paragraphs.isEmpty()) doc.body().prependChild(figure);
            else paragraphs.get(Math.min(image.afterParagraph(), paragraphs.size())-1).after(figure);
        }
        return doc.body().html();
    }
}
