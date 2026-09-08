package com.autoblog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import static com.autoblog.Models.*;

@Repository
public class Store {
    final JdbcTemplate db;
    final ObjectMapper json;
    public Store(JdbcTemplate db, ObjectMapper json) { this.db=db; this.json=json; }
    public JdbcTemplate jdbc() { return db; }
    String encode(List<String> value) {
        try { return json.writeValueAsString(value.stream().map(String::trim).distinct().toList()); }
        catch(Exception e) { throw new IllegalArgumentException("목록 형식이 잘못되었습니다."); }
    }
    List<String> decode(String value) {
        try { return json.readValue(value, new TypeReference<List<String>>(){}); }
        catch(Exception e) { throw new IllegalStateException("저장 데이터 형식 오류", e); }
    }
    Topic mapTopic(ResultSet r, int row) throws SQLException {
        return new Topic(r.getString("id"),r.getString("name"),r.getString("description"),r.getString("instructions"),
            decode(r.getString("keywords")),decode(r.getString("exclusions")),decode(r.getString("tags")),
            r.getString("language"),r.getString("region"),r.getBoolean("active"),r.getBoolean("schedule_enabled"),
            r.getString("cron"),r.getString("timezone"),r.getString("next_run"),r.getString("created_at"),
            r.getString("updated_at"),r.getLong("article_count"),r.getLong("source_count"));
    }
    static final String TOPICS="SELECT t.*, (SELECT COUNT(*) FROM topic_articles ta WHERE ta.topic_id=t.id) article_count, (SELECT COUNT(*) FROM sources s WHERE s.topic_id=t.id) source_count FROM topics t";
    public List<Topic> topics() { return db.query(TOPICS+" ORDER BY t.created_at DESC",this::mapTopic); }
    public Topic topic(String id) { return db.query(TOPICS+" WHERE t.id=?",this::mapTopic,id).stream().findFirst().orElseThrow(()->missing("분야")); }
    public List<Source> sources(String topicId) {
        return db.query("SELECT * FROM sources WHERE topic_id=? ORDER BY created_at",(r,n)->new Source(r.getString("id"),r.getString("topic_id"),r.getString("name"),r.getString("type"),r.getString("media"),r.getString("url"),r.getString("query_text"),r.getString("channel_id"),r.getBoolean("enabled"),r.getString("last_success")),topicId);
    }
    static ResponseStatusException missing(String kind) { return new ResponseStatusException(HttpStatus.NOT_FOUND,kind+"을 찾을 수 없습니다."); }
    static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    public List<Map<String,Object>> rows(String sql, Object... params) {
        return db.query(sql,(r,n)->{ Map<String,Object> m=new LinkedHashMap<>(); var md=r.getMetaData();
            for(int i=1;i<=md.getColumnCount();i++) { String label=md.getColumnLabel(i).toLowerCase(Locale.ROOT); StringBuilder key=new StringBuilder(); boolean upper=false;
                for(char c:label.toCharArray()) { if(c=='_') upper=true; else { key.append(upper?Character.toUpperCase(c):c); upper=false; } }
                Object value=r.getObject(i); if(value instanceof java.sql.Clob clob) value=clob.getSubString(1,(int)clob.length());
                if(label.equals("matched_keywords")) value=decode(r.getString(i));
                m.put(key.toString(),value);
            } return m; },params);
    }
}
