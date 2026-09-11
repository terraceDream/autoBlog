import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
/** Export a stopped H2 backup. Import only into empty, Flyway-created PostgreSQL tables. */
public class ExportH2 {
  static final String[] TABLES={"topics","sources","articles","topic_articles","runs","run_sources","analysis_jobs","blog_drafts","editorial_settings","editorial_runs"};
  public static void main(String[] args)throws Exception {
    try(var db=DriverManager.getConnection("jdbc:h2:file:"+Path.of(args[0]).toAbsolutePath()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;IFEXISTS=TRUE;ACCESS_MODE_DATA=r","sa","");
        var out=Files.newBufferedWriter(Path.of(args[1]),StandardCharsets.UTF_8)) {
      out.write("BEGIN;\nSET LOCAL search_path=autoblog;\nSET LOCAL standard_conforming_strings=on;\n");
      for(String table:TABLES)out.write("DO $$ BEGIN IF EXISTS(SELECT 1 FROM "+table+") THEN RAISE EXCEPTION 'Target table is not empty: "+table+"'; END IF; END $$;\n");
      for(String table:TABLES)try(var query=db.createStatement();var rows=query.executeQuery("SELECT * FROM "+table)) {
        var meta=rows.getMetaData();int n=meta.getColumnCount(),count=0;
        String[] names=new String[n];for(int i=0;i<n;i++)names[i]=meta.getColumnName(i+1);
        while(rows.next()) {
          out.write("INSERT INTO "+table+" ("+String.join(",",names)+") VALUES (");
          for(int i=1;i<=n;i++) {
            if(i>1)out.write(",");Object value=rows.getObject(i);
            if(value==null)out.write("NULL");
            else if(value instanceof Boolean||value instanceof Number)out.write(value.toString());
            else out.write("'"+rows.getString(i).replace("'","''")+"'");
          }
          out.write(");\n");count++;
        }
        System.out.println(table+"="+count);
        out.write("DO $$ BEGIN IF (SELECT count(*) FROM "+table+") <> "+count+" THEN RAISE EXCEPTION 'Count mismatch: "+table+"'; END IF; END $$;\n");
      }
      out.write("COMMIT;\n");
    }
  }
}
