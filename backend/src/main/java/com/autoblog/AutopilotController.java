package com.autoblog;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/topics/{topic}/autopilot")
public class AutopilotController {
 private final AutopilotService service;public AutopilotController(AutopilotService s){service=s;}
 public record Start(String blogUrl){}
 @GetMapping Object board(@PathVariable String topic){return service.board(topic);}
 @PostMapping Object start(@PathVariable String topic,@RequestBody Start input){return service.start(topic,input.blogUrl());}
 @PostMapping("/{id}/resume") Object resume(@PathVariable String topic,@PathVariable String id){return service.resume(topic,id);}
 @PostMapping("/{id}/cancel") Object cancel(@PathVariable String topic,@PathVariable String id){return service.cancel(topic,id);}
 @PostMapping("/{id}/items/{item}/skip") Object skip(@PathVariable String topic,@PathVariable String id,@PathVariable String item){return service.skip(topic,id,item);}
}
