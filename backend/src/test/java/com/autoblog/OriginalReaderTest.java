package com.autoblog;
import com.autoblog.collector.SafeHttp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class OriginalReaderTest {
 private java.util.Map<String,Object> read(String html)throws Exception{
  var http=mock(SafeHttp.class);
  when(http.get(anyString(),anyMap())).thenReturn(html.getBytes(StandardCharsets.UTF_8));
  return new OriginalReader(http).read(new ObjectMapper().readTree("{\"url\":\"https://example.com/a\",\"excerpt\":\"stored excerpt\"}"));
 }
 @Test void readsDivBasedPostAndKeepsEndingAndImages()throws Exception{
  String body="설명 본문 ".repeat(200)+"최종 결론";
  var result=read("<div class='blog-post-content'><p>"+body+"</p><img src='/image.jpg'></div>");
  assertThat(result.get("text")).isEqualTo(body);
  assertThat(result.get("coverage")).isEqualTo("ORIGINAL_EXTRACT");
  assertThat(result.get("imageCandidates").toString()).contains("https://example.com/image.jpg");
 }
 @Test void skipsShortFirstArticle()throws Exception{
  String body="전체 본문 ".repeat(200);
  assertThat(read("<article>추천 카드</article><article>"+body+"</article>").get("text")).isEqualTo(body.trim());
 }
 @Test void refusesUnstructuredPageInsteadOfUsingWholeBody()throws Exception{
  var result=read("<div>"+"메뉴 내용 ".repeat(200)+"</div>");
  assertThat(result.get("errorCode")).isEqualTo("BODY_NOT_FOUND");
  assertThat(result.get("coverage")).isEqualTo("EXCERPT");
 }
 @Test void distinguishesHttpFailureAndTimeout()throws Exception{
  var http=mock(SafeHttp.class);
  var source=new ObjectMapper().readTree("{\"url\":\"https://example.com/a\"}");
  when(http.get(anyString(),anyMap())).thenThrow(new java.io.IOException("외부 서버 응답 HTTP 403"),new java.net.http.HttpTimeoutException("timeout"));
  assertThat(new OriginalReader(http).read(source).get("errorCode")).isEqualTo("HTTP_403");
  assertThat(new OriginalReader(http).read(source).get("errorCode")).isEqualTo("TIMEOUT");
 }
 @Test void preservesEntireArticleBeyondFormerLimit()throws Exception{
  var http=mock(SafeHttp.class);
  String body="본문 내용 ".repeat(3000)+"마지막 검증 결과와 결론";
  when(http.get(anyString(),anyMap())).thenReturn(("<article><p>"+body+"</p></article>").getBytes(StandardCharsets.UTF_8));
  var source=new ObjectMapper().readTree("{\"id\":\"a\",\"url\":\"https://example.com/a\"}");
  var result=new OriginalReader(http).read(source);
  assertThat(result.get("text")).isEqualTo(body);
  assertThat(result.get("coverage")).isEqualTo("ORIGINAL_EXTRACT");
 }
}
