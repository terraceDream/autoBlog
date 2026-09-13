package com.autoblog;

import com.autoblog.collector.SafeHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.autoblog.DraftModels.*;

/** A read-only conversion of a saved draft. It never writes or publishes a draft. */
@Service
public class NaverExportService {
    public record Export(String draftId, String title, String category, List<String> tags,
                         String html, String htmlWithImages, String plainText,
                         List<ExportImage> images, List<ExportTable> tables, List<String> warnings) {}
    public record ExportImage(int index, String url, String sourceUrl, String credit, String license,
                              String licenseUrl, String caption, int afterParagraph, String downloadUrl) {}
    public record ExportTable(int index, String html, String tsv) {}
    public record Download(String filename, String mediaType, byte[] bytes) {}
    private record ImageType(String extension, String mediaType) {}

    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final String BASE_STYLE = "font-family:Arial,'Noto Sans KR',sans-serif;font-size:18px;line-height:1.8;color:#222222;word-break:break-word";
    private static final Safelist BODY_TAGS = new Safelist()
        .addTags("p", "h2", "h3", "ul", "ol", "li", "strong", "b", "em", "i", "blockquote", "pre", "code", "br", "table", "thead", "tbody", "tfoot", "tr", "th", "td", "a")
        .addAttributes("a", "href").addProtocols("a", "href", "http", "https")
        .addAttributes("th", "colspan", "rowspan").addAttributes("td", "colspan", "rowspan");

    private final DraftService drafts;
    private final ObjectMapper json;
    private final SafeHttp http;

    public NaverExportService(DraftService drafts, ObjectMapper json, SafeHttp http) {
        this.drafts = drafts;
        this.json = json;
        this.http = http;
    }

    public Export export(String id) {
        var draft = drafts.get(id);
        JsonNode result = json.valueToTree(draft.get("result"));
        JsonNode input = json.valueToTree(draft.get("input"));
        if (result == null || result.isNull() || result.path("html").asText().isBlank())
            throw Store.bad("작성된 초안이 있어야 네이버용 원고를 준비할 수 있습니다.");
        final Content content;
        try { content = json.treeToValue(result, Content.class); }
        catch (Exception e) { throw Store.bad("저장된 초안 형식을 확인해 주세요."); }

        var warnings = new ArrayList<String>();
        warnings.add("네이버 편집기는 붙여넣을 때 일부 글꼴·크기·표 서식을 바꿀 수 있습니다. 기본 본문 18px, 소제목 22~26px로 준비했습니다.");
        var body = Jsoup.parseBodyFragment(Jsoup.clean(content.html(), "", BODY_TAGS, new Document.OutputSettings().prettyPrint(false)));
        if (body.body().text().isBlank()) throw Store.bad("내보낼 본문이 비어 있습니다.");
        for (var link : body.select("a")) {
            if (!safeLink(link.attr("href"), false)) link.removeAttr("href");
            else link.attr("rel", "noopener noreferrer");
        }
        int paragraphCount = body.select("p").size();
        var candidates = new ArrayList<Image>();
        for (var image : content.images() == null ? List.<Image>of() : content.images()) {
            if (image == null || !image.rightsConfirmed() || !safeLink(image.url(), true)
                || !safeLink(image.sourceUrl(), true) || !safeLink(image.licenseUrl(), true)) {
                warnings.add("사용 조건이 확인되지 않았거나 주소가 올바르지 않은 이미지는 내보내기에서 제외했습니다.");
            } else if (candidates.size() < 3) candidates.add(image);
            else warnings.add("초안의 허용 개수(3개)를 초과한 이미지는 제외했습니다.");
        }
        // Number files in reading order, keeping the same paragraph placement as DraftImages.
        candidates.sort(Comparator.comparingInt(i -> Math.min(Math.max(0, i.afterParagraph()), paragraphCount)));
        var images = new ArrayList<ExportImage>();
        for (var image : candidates) {
            int index = images.size() + 1;
            images.add(new ExportImage(index, image.url(), image.sourceUrl(), image.credit(), image.license(),
                image.licenseUrl(), image.caption(), Math.min(Math.max(0, image.afterParagraph()), paragraphCount),
                "/api/drafts/" + URI.create("/" + id).getRawPath().substring(1) + "/naver-images/" + index));
        }
        if (!images.isEmpty()) warnings.add("외부 이미지는 붙여넣기만으로 저장되지 않을 수 있습니다. 이미지 파일을 내려받아 표시된 위치에 업로드하고 출처·라이선스 문구를 함께 남겨 주세요.");
        var paragraphs = body.select("p");
        // Reverse insertion is important when several images share a paragraph.
        for (int i = images.size() - 1; i >= 0; i--) {
            var image = images.get(i);
            var figure = imageFigure(image);
            if (image.afterParagraph() <= 0 || paragraphs.isEmpty()) body.body().prependChild(figure);
            else paragraphs.get(Math.min(image.afterParagraph(), paragraphs.size()) - 1).after(figure);
        }
        appendSources(body, input, warnings);
        style(body);
        var tables = new ArrayList<ExportTable>();
        for (var table : body.select("table")) tables.add(new ExportTable(tables.size() + 1, table.outerHtml(), tableText(table)));
        if (!tables.isEmpty()) warnings.add("표는 실제 HTML 표로 복사됩니다. 셀 구조가 바뀌면 표별 복사 또는 TSV를 네이버 표에 붙여넣어 주세요.");
        var withImages = body.clone();
        for (var image : images) {
            var slot = withImages.selectFirst("[data-naver-image-slot=\"" + image.index() + "\"]");
            if (slot != null) slot.replaceWith(new Element("img").attr("src", image.url()).attr("alt", image.caption())
                .attr("style", "max-width:100%;height:auto;display:block;margin:12px 0").attr("referrerpolicy", "no-referrer"));
        }
        return new Export(id, content.title(), content.category(), content.tags() == null ? List.of() : content.tags(),
            wrapped(body), wrapped(withImages), plain(body.body()).trim(), List.copyOf(images), List.copyOf(tables), List.copyOf(new LinkedHashSet<>(warnings)));
    }

    public Download image(String id, int index) {
        var image = export(id).images().stream().filter(i -> i.index() == index).findFirst().orElseThrow(() -> Store.missing("이미지"));
        return download(image);
    }

    private Download download(ExportImage image) {
        final byte[] bytes;
        try { bytes = http.get(image.url(), Map.of()); }
        catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "원본 이미지 다운로드에 실패했습니다. 이미지 출처에서 파일을 직접 확인해 주세요.");
        }
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "이미지 파일은 4MB 이하만 내려받을 수 있습니다.");
        var type = imageType(bytes);
        if (type == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PNG·JPEG·GIF·WebP 이미지 파일만 내려받을 수 있습니다. HTML 페이지와 SVG는 이미지 파일로 제공하지 않습니다.");
        return new Download(String.format(Locale.ROOT, "image-%02d.%s", image.index(), type.extension()), type.mediaType(), bytes);
    }

    public Download zip(String id) {
        var export = export(id);
        var htmlImages = Jsoup.parseBodyFragment(export.htmlWithImages());
        var results = new ArrayList<Map<String, Object>>();
        var readme = new StringBuilder("네이버 블로그 수동 게시 자료\n\n")
            .append("이 자료를 준비해도 기존 초안과 티스토리 상태는 변경되지 않습니다. 네이버에 자동 게시하거나 저장하지 않습니다.\n")
            .append("1. title.txt의 제목을 복사합니다.\n2. body.html을 브라우저에서 열어 본문을 복사해 네이버 편집기에 붙여넣습니다. HTML 코드를 붙이지 마세요.\n")
            .append("3. [이미지 NN 삽입] 위치에 images 폴더의 파일을 직접 업로드합니다. 이미지 출처와 라이선스 문구를 유지합니다.\n")
            .append("4. 표가 깨지면 tables의 HTML 표를 따로 복사하거나 TSV를 네이버 표에 붙여넣습니다.\n")
            .append("5. category.txt를 참고해 카테고리를 선택하고 tags.txt의 태그를 입력한 뒤 검토·저장합니다.\n\n")
            .append("body-with-images.html은 성공적으로 내려받은 로컬 이미지가 포함된 미리보기입니다. 붙여넣은 이미지가 네이버에 업로드되었는지 별도로 확인해야 합니다.\n\n이미지 다운로드 결과\n");
        try (var bytes = new ByteArrayOutputStream(); var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var image : export.images()) {
                var result = new LinkedHashMap<String, Object>();
                result.put("index", image.index()); result.put("sourceUrl", image.sourceUrl()); result.put("credit", image.credit());
                result.put("license", image.license()); result.put("licenseUrl", image.licenseUrl()); result.put("caption", image.caption());
                var imageElement = htmlImages.select("img").stream().filter(e -> e.attr("src").equals(image.url())).findFirst().orElse(null);
                try {
                    var file = download(image);
                    String path = "images/" + file.filename();
                    entry(zip, path, file.bytes());
                    if (imageElement != null) imageElement.attr("src", path);
                    result.put("status", "DOWNLOADED"); result.put("filename", path);
                    readme.append(String.format(Locale.ROOT, "- 이미지 %02d: 성공 (%s)\n", image.index(), path));
                } catch (ResponseStatusException e) {
                    if (imageElement != null) imageElement.replaceWith(new Element("p").text("[이미지 " + String.format(Locale.ROOT, "%02d", image.index()) + " 다운로드 실패: 출처에서 직접 확인해 주세요]"));
                    result.put("status", "FAILED"); result.put("message", e.getReason());
                    readme.append(String.format(Locale.ROOT, "- 이미지 %02d: 실패 — %s\n  출처: %s\n", image.index(), e.getReason(), image.sourceUrl()));
                }
                results.add(result);
            }
            if (export.images().isEmpty()) readme.append("- 포함된 이미지 없음\n");
            readme.append("\n확인할 사항\n"); export.warnings().forEach(w -> readme.append("- ").append(w).append('\n'));
            entry(zip, "title.txt", export.title()); entry(zip, "category.txt", export.category()); entry(zip, "tags.txt", String.join(", ", export.tags()));
            entry(zip, "body.txt", export.plainText()); entry(zip, "body.html", document(export.title(), export.html()));
            entry(zip, "body-with-images.html", document(export.title(), htmlImages.body().html()));
            for (var table : export.tables()) {
                entry(zip, String.format(Locale.ROOT, "tables/table-%02d.html", table.index()), document("표 " + table.index(), table.html()));
                entry(zip, String.format(Locale.ROOT, "tables/table-%02d.tsv", table.index()), table.tsv());
            }
            entry(zip, "manifest.json", json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("draftId", export.draftId(), "images", results, "warnings", export.warnings())));
            entry(zip, "README.txt", readme.toString()); zip.finish();
            return new Download("naver-export.zip", "application/zip", bytes.toByteArray());
        } catch (IOException e) { throw new IllegalStateException("네이버 내보내기 파일을 만들지 못했습니다.", e); }
    }

    private static Element imageFigure(ExportImage image) {
        var figure = new Element("div").attr("style", "margin:24px 0");
        figure.appendElement("p").attr("data-naver-image-slot", Integer.toString(image.index()))
            .text(String.format(Locale.ROOT, "[이미지 %02d 삽입: %s]", image.index(), image.caption()));
        var caption = figure.appendElement("p").attr("data-naver-caption", "true").text(image.caption() + " — " + image.credit() + " · ");
        caption.appendElement("a").attr("href", image.sourceUrl()).attr("rel", "noopener noreferrer").text("이미지 출처");
        caption.appendText(" · ").appendElement("a").attr("href", image.licenseUrl()).attr("rel", "noopener noreferrer").text(image.license());
        return figure;
    }

    private static void appendSources(Document doc, JsonNode input, List<String> warnings) {
        var sources = new LinkedHashMap<String, String>();
        if (input != null) for (var source : input.path("sources")) {
            String url = source.path("url").asText();
            if (safeLink(url, false)) sources.putIfAbsent(url, source.path("title").asText(url));
            else warnings.add("주소 형식이 올바르지 않은 참고 자료 링크는 제외했습니다.");
        }
        if (sources.isEmpty()) { warnings.add("저장된 참고 자료 링크가 없습니다. 게시 전에 원문 출처를 확인해 주세요."); return; }
        doc.body().appendElement("h2").text("참고 자료");
        var list = doc.body().appendElement("ul");
        sources.forEach((url, title) -> list.appendElement("li").appendElement("a").attr("href", url).attr("rel", "noopener noreferrer").text(title.isBlank() ? url : title));
    }

    private static void style(Document doc) {
        doc.select("p,ul,ol,li").forEach(e -> e.attr("style", "font-size:18px;line-height:1.8;margin:0 0 18px"));
        doc.select("h2").forEach(e -> e.attr("style", "font-size:26px;line-height:1.5;font-weight:bold;margin:32px 0 16px"));
        doc.select("h3").forEach(e -> e.attr("style", "font-size:22px;line-height:1.5;font-weight:bold;margin:28px 0 12px"));
        doc.select("blockquote").forEach(e -> e.attr("style", "border-left:4px solid #98b69a;padding:12px 18px;margin:24px 0;background-color:#f4f7f4"));
        doc.select("pre,code").forEach(e -> e.attr("style", "font-family:monospace;font-size:16px;white-space:pre-wrap;word-break:break-word"));
        doc.select("a").forEach(e -> e.attr("style", "color:#245baf;text-decoration:underline"));
        doc.select("[data-naver-caption]").forEach(e -> e.attr("style", "font-size:14px;line-height:1.6;color:#666666;margin:8px 0 24px"));
        doc.select("table").forEach(e -> e.attr("style", "border-collapse:collapse;width:100%;margin:24px 0;border:1px solid #b6bfb8;font-size:16px").attr("border", "1").attr("cellpadding", "10").attr("cellspacing", "0"));
        doc.select("th,td").forEach(e -> {
            e.attr("style", "border:1px solid #b6bfb8;padding:10px;text-align:left;vertical-align:top;line-height:1.6" + (e.tagName().equals("th") ? ";background-color:#edf3ed;font-weight:bold" : ""));
            for (String attr : List.of("colspan", "rowspan")) if (e.hasAttr(attr)) e.attr(attr, Integer.toString(span(e, attr)));
        });
    }

    private static String wrapped(Document doc) { return new Element("div").attr("style", BASE_STYLE).html(doc.body().html()).outerHtml(); }
    private static String document(String title, String body) {
        var doc = Document.createShell(""); doc.outputSettings().charset(StandardCharsets.UTF_8);
        doc.head().appendElement("meta").attr("charset", "UTF-8"); doc.title(title); doc.body().html(body);
        return "<!doctype html>\n" + doc.outerHtml();
    }

    private static String plain(Node node) {
        if (node instanceof TextNode text) return text.getWholeText();
        if (!(node instanceof Element element)) return "";
        if (element.tagName().equals("br")) return "\n";
        if (element.tagName().equals("table")) return "\n" + tableText(element) + "\n\n";
        var value = new StringBuilder(); for (var child : element.childNodes()) value.append(plain(child));
        if (element.tagName().equals("a") && element.hasAttr("href")) value.append(" (").append(element.attr("href")).append(')');
        if (element.tagName().equals("li")) value.insert(0, "- ").append('\n');
        else if (Set.of("p", "h2", "h3", "blockquote", "pre", "ul", "ol").contains(element.tagName())) value.append("\n\n");
        return value.toString();
    }

    private static String tableText(Element table) {
        var rows = new ArrayList<List<String>>();
        var occupied = new HashMap<Integer, Integer>();
        for (var row : table.select("tr")) {
            if (row.closest("table") != table) continue;
            var cells = new ArrayList<String>(); int column = 0;
            for (var cell : row.children()) {
                if (!Set.of("th", "td").contains(cell.tagName())) continue;
                while (occupied.getOrDefault(column, 0) > 0) { cells.add(""); column++; }
                int colspan = span(cell, "colspan"), rowspan = span(cell, "rowspan");
                cells.add(plain(cell).replaceAll("\\s+", " ").trim());
                for (int i = 0; i < colspan; i++) {
                    if (i > 0) cells.add("");
                    if (rowspan > 1) occupied.put(column + i, rowspan);
                }
                column += colspan;
            }
            int last = occupied.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
            while (column <= last) { cells.add(""); column++; }
            occupied.replaceAll((c, remaining) -> remaining - 1); occupied.values().removeIf(remaining -> remaining <= 0);
            rows.add(cells);
        }
        int width = rows.stream().mapToInt(List::size).max().orElse(0);
        return String.join("\n", rows.stream().map(row -> { while (row.size() < width) row.add(""); return String.join("\t", row); }).toList());
    }
    private static int span(Element element, String attr) {
        try { return Math.max(1, Math.min(100, Integer.parseInt(element.attr(attr)))); }
        catch (NumberFormatException e) { return 1; }
    }
    private static boolean safeLink(String raw, boolean httpsOnly) {
        try {
            var uri = URI.create(raw);
            return uri.getHost() != null && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 80 || uri.getPort() == 443)
                && ("https".equalsIgnoreCase(uri.getScheme()) || !httpsOnly && "http".equalsIgnoreCase(uri.getScheme()));
        } catch (Exception e) { return false; }
    }
    private static ImageType imageType(byte[] bytes) {
        if (bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})) return new ImageType("png", "image/png");
        if (bytes.length >= 3 && (bytes[0] & 255) == 0xff && (bytes[1] & 255) == 0xd8 && (bytes[2] & 255) == 0xff) return new ImageType("jpg", "image/jpeg");
        if (bytes.length >= 6 && Set.of("GIF87a", "GIF89a").contains(new String(bytes, 0, 6, StandardCharsets.US_ASCII))) return new ImageType("gif", "image/gif");
        if (bytes.length >= 12 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF") && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return new ImageType("webp", "image/webp");
        return null;
    }
    private static void entry(ZipOutputStream zip, String name, String text) throws IOException { entry(zip, name, Objects.toString(text, "").getBytes(StandardCharsets.UTF_8)); }
    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry(); }
}
