package com.autoblog;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/topics/{topic}/editorial")
public class EditorialController {
 private final EditorialWorkbench workbench;
 public EditorialController(EditorialWorkbench workbench){this.workbench=workbench;}
 public record Settings(String audience,boolean automatic){}
 @GetMapping Object board(@PathVariable String topic){return workbench.board(topic);}
 @PutMapping("/settings") Object settings(@PathVariable String topic,@RequestBody Settings input){return workbench.settings(topic,input.audience(),input.automatic());}
 @PostMapping("/collect") Object collect(@PathVariable String topic){return workbench.start(topic);}
 @PostMapping("/recommend") Object recommend(@PathVariable String topic){return workbench.screen(topic,null);}
}
