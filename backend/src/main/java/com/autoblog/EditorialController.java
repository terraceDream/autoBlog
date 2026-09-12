package com.autoblog;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/topics/{topic}/editorial")
public class EditorialController {
 private final EditorialWorkbench workbench;private final TriageService triage;
 public EditorialController(EditorialWorkbench workbench,TriageService triage){this.workbench=workbench;this.triage=triage;}
 public record Settings(String audience,boolean automatic){}
 @GetMapping Object board(@PathVariable String topic){return workbench.board(topic);}
 @PutMapping("/settings") Object settings(@PathVariable String topic,@RequestBody Settings input){return workbench.settings(topic,input.audience(),input.automatic());}
 @PostMapping("/collect") Object collect(@PathVariable String topic){return workbench.start(topic);}
 @PostMapping("/recommend") Object recommend(@PathVariable String topic){return workbench.screen(topic,null);}
 public record Focus(java.util.List<String> ids){}
 public record Classify(int limit){}
 @PostMapping("/focus") Object focus(@PathVariable String topic,@RequestBody Focus input){if(input.ids()==null||input.ids().isEmpty())throw Store.bad("집중 분석할 자료를 선택하세요.");return workbench.screen(topic,null,input.ids());}
 @PostMapping("/triage") Object classify(@PathVariable String topic,@RequestBody Classify input){if(workbench.busy(topic))throw Store.bad("다른 수집·분석이 진행 중입니다.");return triage.start(topic,input.limit());}
 @GetMapping("/triage") Object inbox(@PathVariable String topic,@RequestParam(defaultValue="") String tier,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="priority") String sort,@RequestParam(defaultValue="0") int page){return triage.inbox(topic,tier,category,q,sort,page);}
}
