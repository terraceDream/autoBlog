package com.autoblog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Article IDs are global: collection already deduplicates canonical URLs across topics. */
final class DraftHistory {
 static Set<String> used(Store store,ObjectMapper json){
  Set<String> ids=new HashSet<>();
  for(var row:store.rows("SELECT b.input_json,b.issue_index,a.result_json FROM blog_drafts b JOIN analysis_jobs a ON a.id=b.analysis_id WHERE b.status<>'FAILED' OR b.result_json IS NOT NULL")){
   try{json.readTree(row.get("inputJson").toString()).path("sources").forEach(s->{String id=s.path("id").asText();if(!id.isBlank())ids.add(id);});}catch(Exception ignored){}
   try{json.readTree(row.get("resultJson").toString()).path("issues").path(((Number)row.get("issueIndex")).intValue()).path("sourceIds").forEach(s->ids.add(s.asText()));}catch(Exception ignored){}
  }
  return ids;
 }
}
