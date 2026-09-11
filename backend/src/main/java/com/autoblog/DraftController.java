package com.autoblog;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static com.autoblog.DraftModels.*;
@RestController @RequestMapping("/api/drafts")
public class DraftController {
 private final DraftService service;public DraftController(DraftService s){service=s;}
 @GetMapping Object list(@RequestParam String analysisId,@RequestParam int issueIndex){return service.list(analysisId,issueIndex);}
 @PostMapping Object create(@Valid @RequestBody Create r){return service.create(r);}
 @GetMapping("/{id}") Object get(@PathVariable String id){return service.get(id);}
 @PutMapping("/{id}") Object save(@PathVariable String id,@Valid @RequestBody Content r){return service.save(id,r);}
 @PostMapping("/{id}/tistory") Object send(@PathVariable String id,@Valid @RequestBody Publish r){return service.publish(id,r);}
 @PostMapping("/{id}/retry") Object retry(@PathVariable String id,@RequestBody java.util.Map<String,Boolean> r){return service.retry(id,Boolean.TRUE.equals(r.get("confirmedNotSaved")));}
}
