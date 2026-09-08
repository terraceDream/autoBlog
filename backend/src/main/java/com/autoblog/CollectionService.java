package com.autoblog;

import com.autoblog.collector.Collector;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.autoblog.Models.*;

@Service
public class CollectionService {
    private final Store store;
    private final Map<String,Collector> collectors=new LinkedHashMap<>();
    private final TransactionTemplate tx;
    private final Set<String> active=ConcurrentHashMap.newKeySet();
    // One writer avoids content deduplication races; bounded admission prevents unbounded jobs.
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    public CollectionService(Store store,List<Collector> modules,PlatformTransactionManager manager) {
        this.store=store; modules.forEach(c->collectors.put(c.info().type(),c)); tx=new TransactionTemplate(manager);
    }
    @PostConstruct void recover() { store.jdbc().update("UPDATE runs SET status='FAILED', finished_at=?, message=? WHERE status IN ('QUEUED','RUNNING')",Instant.now().toString(),"서버가 재시작되어 중단되었습니다. 다시 수집해 주세요."); }
    @PreDestroy void stop() { worker.shutdownNow(); }
    public List<Collector.ModuleInfo> modules() { return collectors.values().stream().map(Collector::info).toList(); }
    public boolean running(String topicId) { return active.contains(topicId); }
    public synchronized String start(String topicId,String trigger) {
        Topic topic=store.topic(topicId);
        if(!topic.active()) throw Store.bad("중지된 분야입니다. 활성화 후 수집해 주세요.");
        var sources=store.sources(topicId).stream().filter(Source::enabled).toList();
        if(sources.isEmpty()) throw Store.bad("활성화된 수집 출처를 먼저 추가해 주세요.");
        if(active.contains(topicId)) throw new ResponseStatusException(HttpStatus.CONFLICT,"이미 수집 중인 분야입니다.");
        if(active.size()>=20) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"수집 대기열이 가득 찼습니다.");
        String id=UUID.randomUUID().toString(); active.add(topicId);
        try {
            store.jdbc().update("INSERT INTO runs(id,topic_id,trigger_type,status,started_at,message) VALUES(?,?,?,'QUEUED',?,'')",id,topicId,trigger,Instant.now().toString());
            worker.submit(()->run(id,topic,sources)); return id;
        } catch(RuntimeException ex) { active.remove(topicId); throw ex; }
    }
    void run(String runId,Topic topic,List<Source> sources) {
        int failures=0;
        try {
            store.jdbc().update("UPDATE runs SET status='RUNNING' WHERE id=?",runId);
            for(Source source:sources) {
                String rowId=UUID.randomUUID().toString();
                store.jdbc().update("INSERT INTO run_sources(id,run_id,source_name,source_type,status,fetched,added,duplicates,filtered,message) VALUES(?,?,?,?,'RUNNING',0,0,0,0,'')",rowId,runId,source.name(),source.type());
                try {
                    Collector collector=collectors.get(source.type()); if(collector==null) throw new IllegalArgumentException("지원하지 않는 수집 모듈입니다.");
                    List<Candidate> items=null;
                    for(int attempt=0;attempt<2;attempt++) {
                        try { items=collector.collect(topic,source); break; }
                        catch(java.io.IOException ex) {
                            // Retry transient transport/5xx failures once, never quotas or auth errors.
                            String msg=Objects.toString(ex.getMessage(),"");
                            if(attempt==1||msg.contains("HTTP 4")) throw ex;
                            Thread.sleep(1000);
                        }
                    }
                    final List<Candidate> fetched=Objects.requireNonNull(items);
                    tx.executeWithoutResult(status->{
                        int added=0,duplicates=0,filtered=0;
                        for(Candidate c:fetched) {
                            String text=(c.title()+" "+c.excerpt()).toLowerCase(Locale.ROOT);
                            List<String> matched=topic.keywords().stream().filter(k->text.contains(k.toLowerCase(Locale.ROOT))).toList();
                            boolean excluded=topic.exclusions().stream().anyMatch(k->text.contains(k.toLowerCase(Locale.ROOT)));
                            // Explicit search query is also an inclusion criterion; RSS uses local keyword matching.
                            if(excluded || (source.type().equals("RSS")&&!topic.keywords().isEmpty()&&matched.isEmpty())) { filtered++; continue; }
                            String canonical=canonical(c.url()); if(canonical==null||c.title().isBlank()) { filtered++;continue; }
                            String hash=hash(canonical);
                            List<String> ids=store.jdbc().query("SELECT id FROM articles WHERE url_hash=?",(r,n)->r.getString(1),hash);
                            String articleId;
                            if(ids.isEmpty()) {
                                articleId=UUID.randomUUID().toString();
                                store.jdbc().update("INSERT INTO articles(id,canonical_url,url_hash,title,url,media,source_name,author,external_id,excerpt,coverage,published_at,collected_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",articleId,canonical,hash,cut(c.title(),2000),cut(c.url(),2048),source.media(),cut(source.name(),300),cut(c.author(),500),cut(c.externalId(),2048),cut(c.excerpt(),20000),"EXCERPT",c.publishedAt()==null?null:c.publishedAt().toString(),Instant.now().toString());
                            } else articleId=ids.get(0);
                            Integer count=store.jdbc().queryForObject("SELECT COUNT(*) FROM topic_articles WHERE topic_id=? AND article_id=?",Integer.class,topic.id(),articleId);
                            if(count!=null&&count>0) duplicates++;
                            else { store.jdbc().update("INSERT INTO topic_articles(topic_id,article_id,matched_keywords,status) VALUES(?,?,?,'UNREAD')",topic.id(),articleId,store.encode(matched)); added++; }
                        }
                        String message=source.type().startsWith("NAVER")?"검색어당 최신 최대 100건을 조회했습니다.":source.type().equals("YOUTUBE")?"최신 최대 50건의 영상 정보를 조회했습니다.":"피드의 최신 최대 200건을 조회했습니다.";
                        store.jdbc().update("UPDATE run_sources SET status='SUCCESS', fetched=?,added=?,duplicates=?,filtered=?,message=? WHERE id=?",fetched.size(),added,duplicates,filtered,message,rowId);
                        store.jdbc().update("UPDATE sources SET last_success=? WHERE id=?",Instant.now().toString(),source.id());
                    });
                } catch(Exception e) {
                    failures++;
                    String message=e instanceof IllegalArgumentException||e instanceof java.io.IOException?cut(e.getMessage(),300):"자료 처리 중 오류가 발생했습니다. 피드 형식 또는 서버 상태를 확인해 주세요.";
                    // Provider URLs and credential-bearing exception details must never be logged or persisted.
                    if(message==null||message.contains("https:")||message.contains("http:")) message="외부 자료 요청에 실패했습니다. 출처 주소와 인증 설정을 확인해 주세요.";
                    store.jdbc().update("UPDATE run_sources SET status='FAILED', message=? WHERE id=?",message,rowId);
                }
            }
            store.jdbc().update("UPDATE runs SET status=?,finished_at=?,message=? WHERE id=?",failures==0?"SUCCESS":failures==sources.size()?"FAILED":"PARTIAL",Instant.now().toString(),failures==0?"수집이 완료되었습니다.":failures+"개 출처에서 수집에 실패했습니다.",runId);
        } catch(Exception e) {
            store.jdbc().update("UPDATE runs SET status='FAILED',finished_at=?,message=? WHERE id=?",Instant.now().toString(),"수집 실행이 중단되었습니다.",runId);
        } finally { active.remove(topic.id()); }
    }
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void scheduled() {
        Instant now=Instant.now();
        for(Topic t:store.topics()) {
            if(!t.active()||!t.scheduleEnabled()||t.nextRun()==null||Instant.parse(t.nextRun()).isAfter(now)||running(t.id())) continue;
            try { start(t.id(),"SCHEDULED"); }
            catch(ResponseStatusException e) { /* Invalid/inactive source configurations are skipped until next schedule. */ }
            finally { store.jdbc().update("UPDATE topics SET next_run=? WHERE id=?",nextRun(t.cron(),t.timezone()),t.id()); }
        }
    }
    public static String nextRun(String cron,String timezone) {
        try {
            var expression=CronExpression.parse(cron); var now=ZonedDateTime.now(ZoneId.of(timezone));
            var next=expression.next(now); if(next==null) throw new IllegalArgumentException();
            var after=expression.next(next); if(after!=null&&Duration.between(next,after).getSeconds()<300) throw new IllegalArgumentException();
            return next.toInstant().toString();
        } catch(Exception e) { throw Store.bad("수집 일정과 시간대를 확인해 주세요. 수집 간격은 최소 5분입니다."); }
    }
    static String cut(String s,int length) { if(s==null) return ""; return s.substring(0,Math.min(s.length(),length)); }
    public static String canonical(String raw) {
        try {
            URI u=URI.create(raw).normalize(); if(u.getHost()==null||u.getUserInfo()!=null||!Set.of("http","https").contains(u.getScheme().toLowerCase(Locale.ROOT))) return null;
            String host=u.getHost().toLowerCase(Locale.ROOT); String scheme=u.getScheme().toLowerCase(Locale.ROOT);
            String query=u.getRawQuery();
            if(query!=null) { query=Arrays.stream(query.split("&")).filter(p->!p.toLowerCase(Locale.ROOT).matches("(utm_[^=]*|fbclid|gclid)=.*")).sorted().reduce((a,b)->a+"&"+b).orElse(""); }
            int port=u.getPort(); String authority=host+((port==-1||port==80&&scheme.equals("http")||port==443&&scheme.equals("https"))?"":":"+port);
            String path=u.getRawPath(); if(path==null||path.isEmpty()) path="/";
            return scheme+"://"+authority+path+(query==null||query.isEmpty()?"":"?"+query);
        } catch(Exception e) { return null; }
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
