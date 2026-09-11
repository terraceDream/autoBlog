package com.autoblog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class CommonsImagesTest {
 @Test void acceptsOnlyCompleteReusableImageMetadata()throws Exception{
  var page=new ObjectMapper().readTree("""
   {"pageid":1,"title":"File:Architecture.png","imageinfo":[{"mime":"image/png","width":1000,"url":"https://upload.wikimedia.org/wikipedia/commons/a.png","descriptionurl":"https://commons.wikimedia.org/wiki/File:Architecture.png","extmetadata":{"LicenseShortName":{"value":"CC BY-SA 4.0"},"LicenseUrl":{"value":"https://creativecommons.org/licenses/by-sa/4.0/"},"Artist":{"value":"<a>Example author</a>"},"ImageDescription":{"value":"Architecture diagram"}}}]}
  """);
  var candidate=CommonsImages.parse(page);assertThat(candidate).isNotNull();assertThat(candidate.credit()).isEqualTo("Example author");
  ((com.fasterxml.jackson.databind.node.ObjectNode)page).put("title","File:Goal-based-intelligent-agent-bg.png");assertThat(CommonsImages.parse(page)).isNull();
  ((com.fasterxml.jackson.databind.node.ObjectNode)page).put("title","File:Architecture.png");
  ((com.fasterxml.jackson.databind.node.ObjectNode)page.path("imageinfo").path(0).path("extmetadata").path("LicenseShortName")).put("value","CC BY-NC 4.0");
  assertThat(CommonsImages.parse(page)).isNull();
 }
 @Test void resolvesOnlyCatalogIdsAndRealParagraphPositions(){
  var candidate=new CommonsImages.Candidate("1","Diagram","Context","https://upload.wikimedia.org/a.png","https://commons.wikimedia.org/wiki/File:A.png","Author","CC BY 4.0","https://creativecommons.org/licenses/by/4.0/");
  var selections=List.of(new CommonsImages.Selection("1","개념 설명",2));
  var images=CommonsImages.resolve(selections,List.of(candidate),"<p>첫 문단</p><p>둘째 문단</p>");
  assertThat(images).hasSize(1);assertThat(images.get(0).afterParagraph()).isEqualTo(2);assertThat(images.get(0).url()).isEqualTo(candidate.url());
  assertThatThrownBy(()->CommonsImages.resolve(selections,List.of(candidate),"<p>하나</p>")).isInstanceOf(RuntimeException.class);
  assertThatThrownBy(()->CommonsImages.resolve(List.of(new CommonsImages.Selection("invented","설명",1)),List.of(candidate),"<p>하나</p>")).isInstanceOf(RuntimeException.class);
 }
}
