package com.autoblog.collector;

import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.io.IOException;
import java.util.concurrent.*;

@Component
public class SafeHttp {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public static URI validate(String raw) {
        try {
            URI uri=URI.create(raw);
            if(!("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme())) || uri.getHost()==null || uri.getUserInfo()!=null || (uri.getPort()!=-1 && uri.getPort()!=80 && uri.getPort()!=443))
                throw new IllegalArgumentException();
            for(InetAddress address:InetAddress.getAllByName(uri.getHost())) {
                byte[] b=address.getAddress();
                boolean reservedV4=b.length==4 && ((b[0]&255)==0 || (b[0]&255)>=224 || ((b[0]&255)==100 && ((b[1]&255)>=64 && (b[1]&255)<=127)) || ((b[0]&255)==169 && (b[1]&255)==254));
                boolean privateV6=b.length==16 && ((b[0]&0xfe)==0xfc);
                if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress()||reservedV4||privateV6) throw new IllegalArgumentException();
            }
            return uri;
        } catch(Exception e) { throw new IllegalArgumentException("공개 인터넷의 HTTP(S) 주소만 사용할 수 있습니다. 주소와 DNS를 확인해 주세요."); }
    }
    public byte[] get(String url, Map<String,String> headers) throws Exception {
        URI uri=validate(url);
        for(int redirect=0;redirect<4;redirect++) {
            HttpRequest.Builder b=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25)).header("User-Agent","IssueDesk/0.1 (+RSS reader)").GET();
            headers.forEach(b::header);
            var response=client.send(b.build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                if(response.statusCode()>=300 && response.statusCode()<400) {
                    // Never forward provider credentials to redirect destinations.
                    if(!headers.isEmpty()) throw new IOException("인증 요청의 리디렉션은 지원하지 않습니다.");
                    uri=validate(uri.resolve(response.headers().firstValue("location").orElseThrow()).toString()); continue;
                }
                if(response.statusCode()!=200) throw new IOException("외부 서버 응답 HTTP "+response.statusCode());
                // A body read can outlive the HTTP header timeout; closing the stream bounds it too.
                var timer=Executors.newSingleThreadScheduledExecutor();
                var timeout=timer.schedule(()->{ try { stream.close(); } catch(IOException ignored) {} },25,TimeUnit.SECONDS);
                try { byte[] body=stream.readNBytes(4*1024*1024+1); if(body.length>4*1024*1024) throw new IOException("응답 크기가 4MB를 초과했습니다."); return body; }
                finally { timeout.cancel(false); timer.shutdownNow(); }
            }
        }
        throw new IOException("리디렉션 횟수를 초과했습니다.");
    }
}
