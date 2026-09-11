package com.autoblog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

@Service
public class TistoryBrowserBridge {
    private final String token;
    private final Path extensionPath;
    private long lastSeen;
    private String version="";
    private Job pending;
    @Value("${app.public-origin:}") private String publicOrigin="";
    private static class Job {
        final String id=UUID.randomUUID().toString();
        final Map<String,Object> payload;
        final CompletableFuture<TistoryPublisher.Result> result=new CompletableFuture<>();
        boolean claimed;
        Job(Map<String,Object> payload){this.payload=Map.copyOf(payload);}
    }
    public TistoryBrowserBridge(ObjectMapper json,
            @Value("${app.browser-directory:../.tools/tistory-chrome-extension}") String directory,
            @Value("${server.port:8080}") int port)throws Exception {
        extensionPath=Path.of(directory).toAbsolutePath().normalize();Files.createDirectories(extensionPath);
        Path tokenFile=extensionPath.resolve(".connection-token");
        if(!Files.exists(tokenFile))Files.writeString(tokenFile,UUID.randomUUID()+"-"+UUID.randomUUID(),StandardOpenOption.CREATE_NEW);
        token=Files.readString(tokenFile).trim();
        for(String name:List.of("manifest.json","worker.js","page-actions.js","new-draft.js"))try(var in=new ClassPathResource("chrome-extension/"+name).getInputStream()){
            Files.copy(in,extensionPath.resolve(name),StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(extensionPath.resolve("config.js"),"export const config="+json.writeValueAsString(Map.of("base","http://127.0.0.1:"+port+"/api/tistory-browser","token",token))+";\n");
    }
    public void authorize(String supplied){
        if(supplied==null||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"브라우저 연결 인증이 필요합니다.");
    }
    public byte[] extensionBundle() throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(bytes)) {
            for(String name:List.of("manifest.json","worker.js","page-actions.js","new-draft.js","config.js")) {
                byte[] data=Files.readAllBytes(extensionPath.resolve(name));
                if(!publicOrigin.isBlank()) {
                    var json=new ObjectMapper();
                    if(name.equals("config.js"))data=("export const config="+json.writeValueAsString(Map.of("base",publicOrigin+"/api/tistory-browser","token",token))+";\n").getBytes(StandardCharsets.UTF_8);
                    if(name.equals("manifest.json")) {
                        var manifest=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(data);
                        manifest.putArray("host_permissions").add(publicOrigin+"/*").add("https://*.tistory.com/*");
                        data=json.writeValueAsBytes(manifest);
                    }
                }
                zip.putNextEntry(new java.util.zip.ZipEntry(name));zip.write(data);zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    public synchronized Map<String,Object> status(){return Map.of("ready",ready(),"extensionPath",extensionPath.toString(),"version",version,"updateRequired",!version.equals("1.2.0"));}
    public synchronized void version(String version){this.version=version==null?"":version;}
    private boolean ready(){return lastSeen>0&&System.currentTimeMillis()-lastSeen<90000;}
    public synchronized void ensureConnected(){if(!ready())throw Store.bad("로그인된 Chrome에 Issue Desk 연결 확장 프로그램을 설치하고 확장 아이콘을 눌러 주세요. 새 브라우저는 실행하지 않습니다.");}
    public synchronized Map<String,Object> claim(){
        lastSeen=System.currentTimeMillis();
        if(pending==null||pending.claimed)return Map.of();
        pending.claimed=true;return Map.of("id",pending.id,"payload",pending.payload);
    }
    public synchronized void complete(String id,TistoryPublisher.Result result){
        if(result.status()==null||!Set.of("SAVED_PRIVATE","EDITOR_READY","UNKNOWN").contains(result.status()))throw Store.bad("잘못된 브라우저 결과입니다.");
        if(result.message()==null||result.message().length()>1000||result.url()==null||result.url().length()>2048)throw Store.bad("잘못된 브라우저 결과입니다.");
        if(pending==null||!pending.id.equals(id)||!pending.claimed)throw new ResponseStatusException(HttpStatus.CONFLICT,"종료되었거나 일치하지 않는 작업입니다.");
        if(!result.url().isBlank()&&!result.url().startsWith(pending.payload.get("blogUrl").toString().replaceAll("/$","")+"/"))throw Store.bad("블로그 주소가 일치하지 않습니다.");
        pending.result.complete(result);
    }
    public TistoryPublisher.Result submit(Map<String,Object> payload)throws Exception{
        Job job;
        synchronized(this){ensureConnected();if(pending!=null)throw Store.bad("브라우저 작업이 진행 중입니다.");pending=job=new Job(payload);}
        try{return job.result.get(540,TimeUnit.SECONDS);}
        finally{synchronized(this){if(pending==job)pending=null;}}
    }
}
