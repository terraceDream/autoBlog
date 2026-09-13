package com.autoblog;

import com.autoblog.collector.SafeHttp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;

import static com.autoblog.DraftModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NaverExportServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private DraftService drafts;
    private SafeHttp http;
    private NaverExportService service;
    private Map<String, Object> stored;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1};

    @BeforeEach void setup() {
        drafts = mock(DraftService.class); http = mock(SafeHttp.class);
        service = new NaverExportService(drafts, json, http);
        stored = new LinkedHashMap<>(Map.of("status", "SAVED_PRIVATE", "remoteUrl", "https://blog.tistory.com/11", "updatedAt", "unchanged"));
        stored.put("input", json.valueToTree(Map.of("sources", List.of(
            Map.of("url", "https://example.com/original?q=a&n=b", "title", "원문 <script>"),
            Map.of("url", "javascript:alert(1)", "title", "나쁜 주소")))));
        when(drafts.get("draft-id")).thenAnswer(call -> stored);
    }
    private Image image(String url, int position, boolean confirmed) {
        return new Image(url, "https://example.com/credit", "작가 <script>", "CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/", "그림 <img onerror=x>", position, confirmed);
    }
    private void content(String html, List<Image> images) {
        stored.put("result", json.valueToTree(new Content("제목 </title><script>", html, "AI 개발과 자동화", List.of("AI", "개발"), List.of(), images)));
    }

    @Test void htmlIsSanitizedAndSourcesRemainRealLinksInEveryFormat() {
        content("<p style='font-size:99px' onclick='bad()'>본문 <a href='javascript:bad()'>링크</a></p><script>alert(1)</script><iframe src='https://bad.test'></iframe><img src='https://bad.test/tracker.png'><h2>소제목</h2>", List.of());
        var export = service.export("draft-id");
        for (var html : List.of(export.html(), export.htmlWithImages())) {
            var doc = Jsoup.parseBodyFragment(html);
            assertThat(doc.select("script,iframe,img,[onclick]")).isEmpty();
            assertThat(doc.selectFirst("p").attr("style")).contains("font-size:18px");
            assertThat(doc.selectFirst("h2").attr("style")).contains("font-size:26px");
            assertThat(doc.select("a").eachAttr("href")).contains("https://example.com/original?q=a&n=b").noneMatch(s -> s.startsWith("javascript:"));
        }
        assertThat(export.plainText()).contains("참고 자료", "https://example.com/original?q=a&n=b").doesNotContain("javascript:", "alert(1)");
        assertThat(export.warnings()).anyMatch(s -> s.contains("참고 자료 링크는 제외"));
        verifyNoInteractions(http);
    }

    @Test void imagesAreOrderedByPositionAndCreditsStayWithSlotsAndImages() {
        content("<p>첫 문단</p><p>둘째 문단</p>", List.of(image("https://example.com/late.png", 2, true), image("https://example.com/a.png", 1, true), image("https://example.com/b.png", 1, true)));
        var export = service.export("draft-id");
        assertThat(export.images()).extracting(NaverExportService.ExportImage::url).containsExactly("https://example.com/a.png", "https://example.com/b.png", "https://example.com/late.png");
        assertThat(export.images()).extracting(NaverExportService.ExportImage::index).containsExactly(1, 2, 3);
        assertThat(export.images().get(0).downloadUrl()).isEqualTo("/api/drafts/draft-id/naver-images/1");
        var slots = Jsoup.parseBodyFragment(export.html());
        assertThat(slots.select("img")).isEmpty();
        assertThat(slots.selectFirst("p").nextElementSibling().selectFirst("[data-naver-image-slot]").attr("data-naver-image-slot")).isEqualTo("1");
        assertThat(slots.select("[data-naver-caption]")).hasSize(3);
        assertThat(slots.selectFirst("[data-naver-caption]").text()).contains("작가 <script>", "CC BY 4.0");
        assertThat(Jsoup.parse(export.htmlWithImages()).select("img").eachAttr("src")).containsExactly("https://example.com/a.png", "https://example.com/b.png", "https://example.com/late.png");
        assertThat(export.plainText()).contains("[이미지 01 삽입:", "https://example.com/credit", "https://creativecommons.org/licenses/by/4.0/");
        assertThat(slots.select("script,[onerror]")).isEmpty();
    }

    @Test void tablesRetainCellsAndTsvIncludingMergedCells() {
        content("<p>비교</p><table><tr><th rowspan='2'>도구</th><th colspan='2'>성능</th></tr><tr><th>속도</th><th>비용</th></tr><tr><td>A</td><td>빠름<br>보통</td><td><a href='https://example.com/price'>가격</a></td></tr></table>", List.of());
        var export = service.export("draft-id");
        assertThat(export.tables()).hasSize(1);
        var table = export.tables().get(0);
        assertThat(Jsoup.parse(table.html()).select("table")).hasSize(1);
        assertThat(Jsoup.parse(table.html()).selectFirst("td").attr("style")).contains("border:");
        assertThat(table.tsv()).isEqualTo("도구\t성능\t\n\t속도\t비용\nA\t빠름 보통\t가격 (https://example.com/price)");
        assertThat(export.plainText()).contains(table.tsv());
    }

    @Test void unconfirmedAndUnsafeImagesAreExcludedNotDownloaded() throws Exception {
        content("<p>본문</p>", List.of(image("https://example.com/no.png", 0, false), image("javascript:bad()", 1, true)));
        var export = service.export("draft-id");
        assertThat(export.images()).isEmpty(); assertThat(export.warnings()).anyMatch(s -> s.contains("이미지는 내보내기에서 제외"));
        assertThatThrownBy(() -> service.image("draft-id", 1)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        verifyNoInteractions(http);
    }

    @Test void imageDownloadUsesSavedUrlAndSignatureWithBoundedBytes() throws Exception {
        content("<p>본문</p>", List.of(image("https://example.com/not-an-extension.html", 1, true)));
        when(http.get(anyString(), anyMap())).thenReturn(PNG);
        var download = service.image("draft-id", 1);
        assertThat(download.filename()).isEqualTo("image-01.png"); assertThat(download.mediaType()).isEqualTo("image/png"); assertThat(download.bytes()).isEqualTo(PNG);
        verify(http).get("https://example.com/not-an-extension.html", Map.of());
        when(http.get(anyString(), anyMap())).thenReturn("<html>Denied</html>".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.image("draft-id", 1)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("422");
        when(http.get(anyString(), anyMap())).thenReturn("<svg><script>bad()</script></svg>".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.image("draft-id", 1)).hasMessageContaining("422");
        when(http.get(anyString(), anyMap())).thenReturn(new byte[4 * 1024 * 1024 + 1]);
        assertThatThrownBy(() -> service.image("draft-id", 1)).hasMessageContaining("4MB");
    }

    @Test void acceptedImageSignaturesDetermineDownloadType() throws Exception {
        content("<p>본문</p>", List.of(image("https://example.com/img", 1, true)));
        var samples = Map.of("jpg", new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0}, "gif", "GIF89a....".getBytes(StandardCharsets.US_ASCII), "webp", "RIFFxxxxWEBP".getBytes(StandardCharsets.US_ASCII));
        for (var sample : samples.entrySet()) {
            when(http.get(anyString(), anyMap())).thenReturn(sample.getValue());
            assertThat(service.image("draft-id", 1).filename()).isEqualTo("image-01." + sample.getKey());
        }
    }

    @Test void zipReportsPartialDownloadsAndNeverPackagesErrorPagesAsImages() throws Exception {
        content("<p>본문</p><table><tr><td>A</td><td>B</td></tr></table>", List.of(image("https://example.com/a.png", 0, true), image("https://example.com/denied.jpg", 1, true)));
        when(http.get(eq("https://example.com/a.png"), anyMap())).thenReturn(PNG);
        when(http.get(eq("https://example.com/denied.jpg"), anyMap())).thenReturn("<html>Denied</html>".getBytes(StandardCharsets.UTF_8));
        var archive = service.zip("draft-id"); var files = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive.bytes()), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) files.put(entry.getName(), zip.readAllBytes());
        }
        assertThat(files.keySet()).contains("images/image-01.png", "body.html", "body-with-images.html", "body.txt", "title.txt", "category.txt", "tags.txt", "README.txt", "manifest.json", "tables/table-01.tsv").doesNotContain("images/image-02.jpg");
        assertThat(files.keySet()).noneMatch(s -> s.contains("..") || s.contains("<"));
        assertThat(text(files, "README.txt")).contains("이미지 01: 성공", "이미지 02: 실패");
        var manifest = json.readTree(files.get("manifest.json"));
        assertThat(manifest.path("images").get(1).path("status").asText()).isEqualTo("FAILED");
        assertThat(Jsoup.parse(text(files, "body-with-images.html")).select("img").eachAttr("src")).containsExactly("images/image-01.png");
        assertThat(text(files, "body-with-images.html")).contains("다운로드 실패").doesNotContain("<script>");
        assertThat(text(files, "body.txt")).contains("https://example.com/original?q=a&n=b", "https://creativecommons.org/licenses/by/4.0/");
    }

    @Test void everyExportOperationLeavesSavedDraftAndPublishingStateUntouched() throws Exception {
        content("<p>완료된 본문</p>", List.of(image("https://example.com/a.png", 1, true)));
        when(http.get(anyString(), anyMap())).thenReturn(PNG);
        var snapshot = new LinkedHashMap<>(stored);
        for (var status : List.of("READY", "SAVED_PRIVATE", "UNKNOWN", "EDITOR_READY")) {
            stored.put("status", status); snapshot.put("status", status);
            service.export("draft-id"); service.image("draft-id", 1); service.zip("draft-id");
            assertThat(stored).isEqualTo(snapshot);
        }
        verify(drafts, times(12)).get("draft-id"); verifyNoMoreInteractions(drafts);
    }

    @Test void missingBodyIsClearBadRequestAndDownloadHeadersPreventHtmlInterpretation() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new NaverExportController(service)).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(get("/api/drafts/draft-id/naver-export")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("작성된 초안이 있어야 네이버용 원고를 준비할 수 있습니다."));
        content("<p>본문</p>", List.of(image("https://example.com/a.png", 0, true)));
        when(http.get(anyString(), anyMap())).thenReturn(PNG);
        mvc.perform(get("/api/drafts/draft-id/naver-images/1")).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("image/png"))
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"image-01.png\""))
            .andExpect(header().string("X-Content-Type-Options", "nosniff")).andExpect(header().string("Cache-Control", "no-store"));
    }
    private static String text(Map<String, byte[]> files, String name) { return new String(files.get(name), StandardCharsets.UTF_8); }
}
