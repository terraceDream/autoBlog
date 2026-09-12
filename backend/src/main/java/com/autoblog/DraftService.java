package com.autoblog;

import com.fasterxml.jackson.databind.*;
import jakarta.annotation.*;
import jakarta.validation.Validator;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.autoblog.DraftModels.*;

@Service
public class DraftService {
    private final Store store;private final AnalysisService analysis;private final AnalysisRunner runner;
    private final CommonsImages imageSearch;private final OriginalReader originals;private final ObjectMapper json;private final Validator validator;private final TistoryPublisher publisher;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private volatile boolean busy;private final JsonNode schema;private final String editorialGuide;
    public DraftService(Store s,AnalysisService a,AnalysisRunner r,OriginalReader o,ObjectMapper j,Validator v,TistoryPublisher p,CommonsImages imageSearch)throws Exception{
        this.imageSearch=imageSearch;
        store=s;analysis=a;runner=r;originals=o;json=j;validator=v;publisher=p;
        try(var in=new ClassPathResource("draft-schema.json").getInputStream()){schema=json.readTree(in);}
        try(var in=new ClassPathResource("editorial-guide.md").getInputStream()){editorialGuide=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
    }
    @PostConstruct void recover(){store.jdbc().update("UPDATE blog_drafts SET status='FAILED',message=? WHERE status='WRITING'","서버 재시작으로 작성이 중단되었습니다.");store.jdbc().update("UPDATE blog_drafts SET status='UNKNOWN',message=? WHERE status='SENDING'","전송 중 서버가 재시작되었습니다. 티스토리에서 저장 여부를 확인하세요. 자동 재전송하지 않습니다.");}
    @PreDestroy void close(){executor.shutdownNow();}
    public boolean isBusy(){return busy;}
    String encode(Object x){try{return json.writeValueAsString(x);}catch(Exception e){throw new IllegalStateException(e);}}
    public List<Map<String,Object>> list(String analysisId,int index){analysis.job(analysisId);var rows=store.rows("SELECT * FROM blog_drafts WHERE analysis_id=? AND issue_index=? ORDER BY created_at DESC",analysisId,index);rows.forEach(this::decode);return rows;}
    void decode(Map<String,Object> row){for(String key:List.of("inputJson","resultJson")){Object value=row.remove(key);try{row.put(key.equals("inputJson")?"input":"result",value==null?null:json.readTree(value.toString()));}catch(Exception e){throw new IllegalStateException(e);}}if(row.get("result") instanceof JsonNode result&&!result.isNull())try{row.put("previewHtml",DraftImages.render(json.treeToValue(result,Content.class)));}catch(Exception e){throw new IllegalStateException(e);}}
    public Map<String,Object> get(String id){var rows=store.rows("SELECT * FROM blog_drafts WHERE id=?",id);if(rows.isEmpty())throw Store.missing("초안");var row=rows.get(0);decode(row);return row;}
    public synchronized Object create(Create request){
        return create(request,null);
    }
    public synchronized Object create(Create request,String automationItemId){
        if(automationItemId!=null){var existing=store.rows("SELECT id FROM blog_drafts WHERE automation_item_id=?",automationItemId);if(!existing.isEmpty())return Map.of("id",existing.get(0).get("id"));}
        if(busy||runner.isBusy())throw Store.bad("다른 AI 분석 또는 글 작성·전송이 진행 중입니다. 완료 후 다시 시도해 주세요.");
        var job=analysis.job(request.analysisId());if(!"SUCCESS".equals(job.get("status")))throw Store.bad("완료된 분석만 글로 작성할 수 있습니다.");
        JsonNode result=(JsonNode)job.get("result"),input=(JsonNode)job.get("input");
        if(request.issueIndex()>=result.path("issues").size())throw Store.bad("소재를 확인해 주세요.");
        JsonNode issue=result.path("issues").get(request.issueIndex());
        if(issue.path("sourceIds").size()>5)throw Store.bad("원문 5건 이하의 소재를 선택해 주세요. 입력량을 제한합니다.");
        Set<String> ids=new HashSet<>();issue.path("sourceIds").forEach(x->ids.add(x.asText()));List<JsonNode> selected=new ArrayList<>();
        input.path("sources").forEach(x->{if(ids.contains(x.path("id").asText()))selected.add(x);});
        String id=UUID.randomUUID().toString(),now=Instant.now().toString();
        Map<String,Object> snapshot=Map.of("issue",issue,"sources",selected,"direction",request.direction());
        store.jdbc().update("INSERT INTO blog_drafts(id,analysis_id,issue_index,direction,status,input_json,message,created_at,updated_at,automation_item_id) VALUES(?,?,?,?,'WRITING',?,'선택한 원문을 확인하고 글을 작성합니다.',?,?,?)",id,request.analysisId(),request.issueIndex(),request.direction(),encode(snapshot),now,now,automationItemId);
        busy=true;executor.submit(()->write(id,issue,selected,request.direction()));return Map.of("id",id);
    }
    void write(String id,JsonNode issue,List<JsonNode> selected,String direction){try{
        List<Map<String,Object>> sources=new ArrayList<>();for(var source:selected){if(Thread.currentThread().isInterrupted())throw new InterruptedException();sources.add(originals.read(source));}
        var imageCatalog=imageSearch.search(issue);
        var input=Map.of("issue",issue,"sources",sources,"direction",direction,"imageCatalog",imageCatalog);
        store.jdbc().update("UPDATE blog_drafts SET input_json=? WHERE id=?",encode(input),id);
        if(sources.isEmpty()||sources.stream().anyMatch(source->!"ORIGINAL_EXTRACT".equals(source.get("coverage"))))
            throw new AnalysisRunner.Failure("FAILED","원문 본문을 가져오지 못한 자료가 있어 작성을 중단했습니다. 저장된 발췌문으로 대체 작성하지 않았습니다. 자료별 원문 접근 상태를 확인해 주세요.");
        String prompt=editorialGuide+"\nDo not use tools or fetch anything.\nDATA:\n"+encode(input);
        var out=runner.analyze(prompt,schema,()->Thread.currentThread().isInterrupted());
        DraftModels.Generated generated=json.readerFor(DraftModels.Generated.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(out.json());
        var images=CommonsImages.resolve(generated.imageSelections(),imageCatalog.candidates(),generated.html());
        var checks=new ArrayList<>(generated.checks());if(images.isEmpty()&&checks.size()<20)checks.add("자동 이미지 미삽입: "+(imageCatalog.candidates().isEmpty()?imageCatalog.message():"본문과 관련성이 충분한 후보를 선택하지 못했습니다."));
        Content content=clean(new Content(generated.title(),generated.html(),generated.category(),generated.tags(),checks,images));store.jdbc().update("UPDATE blog_drafts SET status='READY',result_json=?,input_tokens=?,output_tokens=?,message=?,updated_at=? WHERE id=?",encode(content),out.inputTokens(),out.outputTokens(),"초안이 준비되었습니다. 수정 후 티스토리에 비공개 저장할 수 있습니다.",Instant.now().toString(),id);
    }catch(Exception e){store.jdbc().update("UPDATE blog_drafts SET status='FAILED',message=?,updated_at=? WHERE id=?",e instanceof AnalysisRunner.Failure?e.getMessage():"원문 확인 또는 글 작성에 실패했습니다. 자동 재시도하지 않았습니다.",Instant.now().toString(),id);}finally{busy=false;}}
    Content clean(Content content){if(!validator.validate(content).isEmpty())throw Store.bad("제목·본문·태그 길이를 확인해 주세요.");String html=Jsoup.clean(content.html(),new Safelist().addTags("p","h2","h3","ul","ol","li","strong","em","blockquote","pre","code","br","table","thead","tbody","tr","th","td"));if(Jsoup.parse(html).text().isBlank())throw Store.bad("본문이 비어 있습니다.");var images=content.images()==null?List.<Image>of():content.images();DraftImages.validate(images);return new Content(content.title(),html,content.category(),content.tags(),content.checks(),images);}
    public synchronized Object retry(String id,boolean confirmedNotSaved){
        var draft=get(id);String status=draft.get("status").toString();
        if(busy||!Set.of("EDITOR_READY","UNKNOWN").contains(status))throw Store.bad("현재 상태에서는 재시도를 준비할 수 없습니다.");
        if(status.equals("UNKNOWN")&&!confirmedNotSaved)throw Store.bad("티스토리 글 목록에서 저장되지 않았는지 먼저 확인해 주세요.");
        store.jdbc().update("UPDATE blog_drafts SET status='READY',message=?,updated_at=? WHERE id=?","기존 초안으로 다시 작성할 수 있습니다. 이전 작성 탭은 자동으로 닫지 않습니다.",Instant.now().toString(),id);return get(id);
    }
    public synchronized Object save(String id,Content content){var draft=get(id);if(!Set.of("READY","EDITOR_READY").contains(draft.get("status")))throw Store.bad("수정 가능한 초안이 아닙니다.");store.jdbc().update("UPDATE blog_drafts SET result_json=?,updated_at=? WHERE id=?",encode(clean(content)),Instant.now().toString(),id);return get(id);}
    public synchronized Object publish(String id,Publish request){
        publisher.ensureConnected();
        var draft=get(id);if(busy||!Set.of("READY","EDITOR_READY").contains(draft.get("status")))throw Store.bad("준비된 초안만 전송할 수 있습니다. 저장 여부가 불확실하면 글 목록 확인 후 재시도하세요.");
        store.jdbc().update("UPDATE blog_drafts SET status='SENDING',blog_url=?,message=?,updated_at=? WHERE id=?",request.blogUrl(),"티스토리 브라우저에서 로그인과 비공개 저장을 진행합니다.",Instant.now().toString(),id);
        busy=true;executor.submit(()->{try{
            JsonNode content=(JsonNode)draft.get("result"),input=(JsonNode)draft.get("input");
            StringBuilder html=new StringBuilder(DraftImages.render(json.treeToValue(content,Content.class))).append("<h2>참고 자료</h2><ul>");
            for(var s:input.path("sources")){String url=s.path("url").asText();if(!url.startsWith("https://")&&!url.startsWith("http://"))continue;var link=new org.jsoup.nodes.Element("a").attr("href",url).attr("rel","noopener noreferrer").text(s.path("title").asText());html.append(new org.jsoup.nodes.Element("li").appendChild(link).outerHtml());}html.append("</ul>");
            var result=publisher.send(Map.of("blogUrl",request.blogUrl(),"title",content.path("title").asText(),"html",html.toString(),"category",content.path("category").asText(),"tags",content.path("tags")));
            store.jdbc().update("UPDATE blog_drafts SET status=?,message=?,remote_url=?,updated_at=? WHERE id=?",result.status(),result.message(),result.url(),Instant.now().toString(),id);
        }catch(Exception e){store.jdbc().update("UPDATE blog_drafts SET status='UNKNOWN',message=?,updated_at=? WHERE id=?","전송 결과를 확인하지 못했습니다. 티스토리 글 목록에서 확인하세요. 중복 방지를 위해 자동 재전송하지 않습니다.",Instant.now().toString(),id);}finally{busy=false;}});return get(id);
    }
}
