package com.autoblog;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/drafts")
public class NaverExportController {
    private final NaverExportService service;
    public NaverExportController(NaverExportService service) { this.service = service; }

    @GetMapping("/{id}/naver-export")
    ResponseEntity<NaverExportService.Export> export(@PathVariable String id) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(service.export(id));
    }

    @GetMapping("/{id}/naver-export.zip")
    ResponseEntity<byte[]> zip(@PathVariable String id) { return download(service.zip(id)); }

    @GetMapping("/{id}/naver-images/{index}")
    ResponseEntity<byte[]> image(@PathVariable String id, @PathVariable int index) { return download(service.image(id, index)); }

    private static ResponseEntity<byte[]> download(NaverExportService.Download file) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff")
            .contentLength(file.bytes().length).body(file.bytes());
    }
}
