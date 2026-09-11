package com.autoblog;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/tistory-browser")
public class TistoryBrowserController {
    private final TistoryBrowserBridge bridge;
    public TistoryBrowserController(TistoryBrowserBridge bridge){this.bridge=bridge;}
    @GetMapping("/status") Object status(){return bridge.status();}
    @GetMapping(value="/extension.zip",produces="application/zip") org.springframework.http.ResponseEntity<byte[]> extension() throws Exception {
        return org.springframework.http.ResponseEntity.ok().header("Content-Disposition","attachment; filename=issuedesk-chrome.zip").header("Cache-Control","no-store").body(bridge.extensionBundle());
    }
    @PostMapping("/claim") Object claim(@RequestHeader(value="X-IssueDesk-Token",required=false) String token,@RequestBody(required=false) Map<String,String> info){bridge.authorize(token);bridge.version(info==null?"":info.get("version"));return bridge.claim();}
    @PostMapping("/jobs/{id}/result") Map<String,Boolean> result(@RequestHeader(value="X-IssueDesk-Token",required=false) String token,@PathVariable String id,@RequestBody TistoryPublisher.Result result){bridge.authorize(token);bridge.complete(id,result);return Map.of("ok",true);}
}
