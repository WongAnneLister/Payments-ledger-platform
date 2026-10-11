package com.paymentsledger.pr20.performance;

import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.*;

/** Test-only diagnostic wrapper. Query budgets and SQL semantics are unchanged. */
final class ObservedJdbcTemplate extends JdbcTemplate {
    private final Path evidence;
    private final String database;
    private final ThreadLocal<Boolean> active=new ThreadLocal<>();
    ObservedJdbcTemplate(DataSource source,Path evidence,String database){super(source);this.evidence=evidence;this.database=database;}
    @Override public <T> T query(String sql,ResultSetExtractor<T> extractor){
        return observed(sql,()->super.query(sql,extractor));
    }
    @Override public <T> T queryForObject(String sql,Class<T> type){
        return observed(sql,()->super.queryForObject(sql,type));
    }
    @Override public <T> T queryForObject(String sql,RowMapper<T> mapper){
        return observed(sql,()->super.queryForObject(sql,mapper));
    }
    @Override public Map<String,Object> queryForMap(String sql){
        return observed(sql,()->super.queryForMap(sql));
    }
    private <T> T observed(String sql,Supplier<T> action){
        if(Boolean.TRUE.equals(active.get()))return action.get();
        active.set(true);RuntimeException original=null;
        long started=System.nanoTime();var record=new LinkedHashMap<String,Object>();
        record.put("atUtc",Instant.now().toString());record.put("database",database);record.put("sql",sql);
        try{T value=action.get();record.put("status","PASS");return value;}
        catch(RuntimeException failure){
            original=failure;
            record.put("status","FAIL");var text=new StringWriter();failure.printStackTrace(new PrintWriter(text));
            for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sqlFailure)text.append("\nSQLSTATE=").append(String.valueOf(sqlFailure.getSQLState()));
            record.put("failure",text.toString());throw failure;
        }finally{
            record.put("elapsedMillis",(System.nanoTime()-started)/1e6);
            try{synchronized(ObservedJdbcTemplate.class){Files.writeString(evidence,Pr20PerformanceExperiment.JSON.writeValueAsString(record)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}}
            catch(IOException failure){if(original!=null)original.addSuppressed(failure);else throw new IllegalStateException("Query diagnostic evidence unavailable",failure);}
            finally{active.remove();}
        }
    }
}
