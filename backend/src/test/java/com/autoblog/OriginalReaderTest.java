package com.autoblog;
import com.autoblog.collector.SafeHttp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class OriginalReaderTest {
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
