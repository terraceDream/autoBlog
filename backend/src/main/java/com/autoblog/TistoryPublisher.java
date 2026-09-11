package com.autoblog;
import org.springframework.stereotype.Component;
import java.util.Map;
/** Uses the extension in the existing Chrome session. Never launches a browser. */
@Component
public class TistoryPublisher {
 public record Result(String status,String message,String url){}
 private final TistoryBrowserBridge bridge;
 public TistoryPublisher(TistoryBrowserBridge bridge){this.bridge=bridge;}
 public void ensureConnected(){bridge.ensureConnected();}
 public Result send(Map<String,Object> payload)throws Exception{return bridge.submit(payload);}
}
