package com.autoblog;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static com.autoblog.AnalysisModels.*;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {
    private final AnalysisService service;private final AnalysisRunner runner;
    public AnalysisController(AnalysisService service,AnalysisRunner runner){this.service=service;this.runner=runner;}
    @GetMapping("/status") Object status(){return runner.availability();}
    @PostMapping("/preview") Object preview(@Valid @RequestBody Request request){return service.preview(request);}
    @PostMapping("/jobs") @ResponseStatus(HttpStatus.ACCEPTED) Object start(@Valid @RequestBody Start request){return service.start(request);}
    @GetMapping("/jobs") Object list(@RequestParam String topicId,@RequestParam(defaultValue="0") int page){return service.jobs(topicId,page);}
    @GetMapping("/jobs/{id}") Object job(@PathVariable String id){return service.job(id);}
    @PostMapping("/jobs/{id}/cancel") @ResponseStatus(HttpStatus.NO_CONTENT) void cancel(@PathVariable String id){service.cancel(id);}
}
