package com.paymentsledger.pr20.performance;

import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.*;

/** Test-only diagnostic wrapper. Query budgets and SQL semantics are unchanged. */
final class ObservedJdbcTemplate extends JdbcTemplate {
    private final Path evidence;
    private final String database;
    ObservedJdbcTemplate(DataSource source,Path evidence,String database){super(source);this.evidence=evidence;this.database=database;}
    @Override public <T> T query(String sql,ResultSetExtractor<T> extractor){
        long started=System.nanoTime();var record=new LinkedHashMap<String,Object>();
        record.put("atUtc",Instant.now().toString());record.put("database",database);record.put("sql",sql);
        try{T value=super.query(sql,extractor);record.put("status","PASS");return value;}
        catch(RuntimeException failure){
            record.put("status","FAIL");var text=new StringWriter();failure.printStackTrace(new PrintWriter(text));
            for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sqlFailure)text.append("\nSQLSTATE=").append(String.valueOf(sqlFailure.getSQLState()));
            record.put("failure",text.toString());throw failure;
        }finally{
            record.put("elapsedMillis",(System.nanoTime()-started)/1e6);
            try{synchronized(ObservedJdbcTemplate.class){Files.writeString(evidence,Pr20PerformanceExperiment.JSON.writeValueAsString(record)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}}
            catch(IOException failure){throw new IllegalStateException("Query diagnostic evidence unavailable",failure);}
        }
    }
}
