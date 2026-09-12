package com.autoblog;

import com.autoblog.collector.SafeHttp;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static com.autoblog.Models.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    final Store store; final CollectionService collection; final AnalysisService analysis;
    public ApiController(Store store,CollectionService collection,AnalysisService analysis) {this.store=store;this.collection=collection;this.analysis=analysis;}
    @GetMapping("/health") Object health() { return Map.of("status","UP","name","Issue Desk"); }
    @GetMapping("/modules") Object modules() { return collection.modules(); }
    @GetMapping("/topics") Object topics() { return store.topics(); }
    @PostMapping("/topics") @ResponseStatus(HttpStatus.CREATED)
    Object create(@Valid @RequestBody TopicInput input) { return save(null,input); }
    @PutMapping("/topics/{id}") Object update(@PathVariable String id,@Valid @RequestBody TopicInput input) {store.topic(id);return save(id,input);}
    Object save(String id,TopicInput p) {
        String next=CollectionService.nextRun(p.cron(),p.timezone()); String now=Instant.now().toString();
        if(id==null) { id=UUID.randomUUID().toString();
            store.jdbc().update("INSERT INTO topics(id,name,description,instructions,keywords,exclusions,tags,language,region,active,schedule_enabled,cron,timezone,next_run,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,p.name().trim(),p.description(),p.instructions(),store.encode(p.keywords()),store.encode(p.exclusions()),store.encode(p.tags()),p.language(),p.region(),p.active(),p.scheduleEnabled(),p.cron(),p.timezone(),p.scheduleEnabled()&&p.active()?next:null,now,now);
        } else {
            store.jdbc().update("UPDATE topics SET name=?,description=?,instructions=?,keywords=?,exclusions=?,tags=?,language=?,region=?,active=?,schedule_enabled=?,cron=?,timezone=?,next_run=?,updated_at=? WHERE id=?",p.name().trim(),p.description(),p.instructions(),store.encode(p.keywords()),store.encode(p.exclusions()),store.encode(p.tags()),p.language(),p.region(),p.active(),p.scheduleEnabled(),p.cron(),p.timezone(),p.scheduleEnabled()&&p.active()?next:null,now,id);
        }
        return store.topic(id);
    }
    @DeleteMapping("/topics/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteTopic(@PathVariable String id) { store.topic(id);guard(id); store.jdbc().update("DELETE FROM topics WHERE id=?",id); }
    void guard(String id) { if(collection.running(id)||analysis.running(id)) throw new ResponseStatusException(HttpStatus.CONFLICT,"수집·분석 완료 후 변경해 주세요."); }
    @GetMapping("/topics/{id}/sources") Object sources(@PathVariable String id) {store.topic(id);return store.sources(id);}
    @PostMapping("/topics/{id}/sources") @ResponseStatus(HttpStatus.CREATED)
    Object addSource(@PathVariable String id,@Valid @RequestBody SourceInput input) {store.topic(id);guard(id); return saveSource(id,null,input);}
    @PutMapping("/topics/{id}/sources/{sourceId}") Object updateSource(@PathVariable String id,@PathVariable String sourceId,@Valid @RequestBody SourceInput input) {
        guard(id); if(store.sources(id).stream().noneMatch(s->s.id().equals(sourceId))) throw Store.missing("출처"); return saveSource(id,sourceId,input);
    }
    Object saveSource(String topicId,String id,SourceInput p) {
        if(p.type().equals("RSS")) { try { SafeHttp.validate(p.url()); } catch(IllegalArgumentException e) {throw Store.bad(e.getMessage());} }
        if(!p.channelId().isEmpty()&&!p.channelId().matches("UC[A-Za-z0-9_-]{22}")) throw Store.bad("YouTube 채널 ID는 UC로 시작하는 24자리 값입니다.");
        String media=switch(p.type()) {case "YOUTUBE"->"VIDEO";case "NAVER_NEWS"->"NEWS";case "NAVER_BLOG"->"BLOG";default->p.media();};
        if(id==null) { id=UUID.randomUUID().toString(); store.jdbc().update("INSERT INTO sources(id,topic_id,name,type,media,url,query_text,channel_id,enabled,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",id,topicId,p.name().trim(),p.type(),media,p.url().trim(),p.query().trim(),p.channelId().trim(),p.enabled(),Instant.now().toString()); }
        else store.jdbc().update("UPDATE sources SET name=?,type=?,media=?,url=?,query_text=?,channel_id=?,enabled=?,last_success=NULL WHERE id=? AND topic_id=?",p.name().trim(),p.type(),media,p.url().trim(),p.query().trim(),p.channelId().trim(),p.enabled(),id,topicId);
        final String saved=id; return store.sources(topicId).stream().filter(s->s.id().equals(saved)).findFirst().orElseThrow();
    }
    @DeleteMapping("/topics/{id}/sources/{sourceId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteSource(@PathVariable String id,@PathVariable String sourceId) {guard(id); if(store.jdbc().update("DELETE FROM sources WHERE id=? AND topic_id=?",sourceId,id)==0) throw Store.missing("출처"); }
    @PostMapping("/topics/{id}/collect") @ResponseStatus(HttpStatus.ACCEPTED)
    Object collect(@PathVariable String id) {return Map.of("runId",collection.start(id,"MANUAL"));}
    @GetMapping("/articles") Object articles(@RequestParam String topicId,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String media,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String source,@RequestParam(defaultValue="") String from,@RequestParam(defaultValue="") String to,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="desc") String sort) {
        store.topic(topicId); if(page<0||page>100000||size<1||size>100) throw Store.bad("페이지 범위를 확인해 주세요.");
        StringBuilder where=new StringBuilder(" FROM articles a JOIN topic_articles ta ON ta.article_id=a.id WHERE ta.topic_id=?"); List<Object> args=new ArrayList<>();args.add(topicId);
        if(!q.isBlank()) {where.append(" AND LOWER(a.title) LIKE ? ESCAPE '!'");args.add("%"+q.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
        if(!media.isBlank()) {where.append(" AND a.media=?");args.add(media);}
        if(!source.isBlank()) {where.append(" AND a.source_name=?");args.add(source);}
        if(!status.isBlank()) {where.append(" AND ta.status=?");args.add(status);} else where.append(" AND ta.status<>'HIDDEN'");
        try {
            if(!from.isBlank()) {where.append(" AND a.published_at>=?");args.add(LocalDate.parse(from).atStartOfDay(ZoneId.of(store.topic(topicId).timezone())).toInstant().toString());}
            if(!to.isBlank()) {where.append(" AND a.published_at<?");args.add(LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneId.of(store.topic(topicId).timezone())).toInstant().toString());}
        } catch(java.time.DateTimeException e) {throw Store.bad("조회 날짜를 확인해 주세요.");}
        long total=Objects.requireNonNull(store.jdbc().queryForObject("SELECT COUNT(*)"+where,Long.class,args.toArray()));
        args.add(size);args.add(page*size);
        var rows=store.rows("SELECT a.*,ta.status,ta.matched_keywords,ta.triage_tier,ta.triage_json"+where+" ORDER BY a.published_at "+(sort.equals("asc")?"ASC":"DESC")+" NULLS LAST,a.collected_at DESC,a.id LIMIT ? OFFSET ?",args.toArray());
        for(var row:rows){Object raw=row.remove("triageJson");try{row.put("triage",raw==null?null:store.mapper().readTree(raw.toString()));}catch(Exception ignored){row.put("triage",null);}}
        return new Page<>(rows,total,page,size);
    }
    @GetMapping("/topics/{id}/stats") Object stats(@PathVariable String id) {
        store.topic(id);
        return Map.of("statuses",store.rows("SELECT status,COUNT(*) count FROM topic_articles WHERE topic_id=? GROUP BY status",id),"sources",store.rows("SELECT DISTINCT a.source_name FROM articles a JOIN topic_articles ta ON ta.article_id=a.id WHERE ta.topic_id=? ORDER BY a.source_name",id),"running",collection.running(id));
    }
    @PatchMapping("/articles/status") @Transactional Object status(@Valid @RequestBody StatusInput p) {
        store.topic(p.topicId()); int updated=0;
        for(String id:new LinkedHashSet<>(p.ids())) { int n=store.jdbc().update("UPDATE topic_articles SET status=? WHERE topic_id=? AND article_id=?",p.status(),p.topicId(),id);if(n==0) throw Store.missing("수집 항목");updated+=n; }
        return Map.of("updated",updated);
    }
    @GetMapping("/runs") Object runs(@RequestParam String topicId,@RequestParam(defaultValue="0") int page) {
        store.topic(topicId);if(page<0||page>100000) throw Store.bad("페이지 범위를 확인해 주세요.");
        var rows=store.rows("SELECT * FROM runs WHERE topic_id=? ORDER BY started_at DESC LIMIT 20 OFFSET ?",topicId,page*20);
        rows.forEach(r->r.put("sources",store.rows("SELECT * FROM run_sources WHERE run_id=?",r.get("id"))));
        return new Page<>(rows,Objects.requireNonNull(store.jdbc().queryForObject("SELECT COUNT(*) FROM runs WHERE topic_id=?",Long.class,topicId)),page,20);
    }
}
