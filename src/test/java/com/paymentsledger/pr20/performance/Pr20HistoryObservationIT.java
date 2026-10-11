package com.paymentsledger.pr20.performance;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class Pr20HistoryObservationIT {
    @Test void measuredDatabaseCutoffExcludesNewUnknownTrafficAndRetainsLiveHistoryChanges() throws Exception {
        try(var db=new PostgreSQLContainer<>("postgres:16").withDatabaseName("pr20_history_observation").withUsername("pr20_admin").withPassword("disposable_only").withInitScript("db/pr02-runtime-role.sql")){
            db.start();Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).schemas("platform").defaultSchema("platform").locations("classpath:db/migration").load().migrate();
            try(var connection=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword())){
                var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
                PerformanceFixture.seed(jdbc,8,"https://merchant.test/pr20",n->{});
                Instant cutoff=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
                assertThat(Pr20PerformanceExperiment.historyUnknownCount(jdbc,cutoff)).isEqualTo(8);
                UUID order=UUID.randomUUID(),payment=UUID.randomUUID();Instant fresh=cutoff.plusSeconds(1);
                jdbc.execute("INSERT INTO platform.business_order SELECT (jsonb_populate_record(NULL::platform.business_order,to_jsonb(o)||jsonb_build_object('id','"+order+"','order_key','pr20-load-after-cutoff','created_at','"+fresh+"'))).* FROM platform.business_order o LIMIT 1");
                jdbc.execute("INSERT INTO platform.payment SELECT (jsonb_populate_record(NULL::platform.payment,to_jsonb(p)||jsonb_build_object('id','"+payment+"','business_order_id','"+order+"','created_at','"+fresh+"','updated_at','"+fresh+"','unresolved_since','"+fresh+"','expires_at','"+fresh.plusSeconds(3600)+"'))).* FROM platform.payment p LIMIT 1");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.payment WHERE resolution_status='UNKNOWN'",Long.class)).isEqualTo(9);
                assertThat(Pr20PerformanceExperiment.historyUnknownCount(jdbc,cutoff)).isEqualTo(8);
                var state=Pr20PerformanceExperiment.paymentObservations(jdbc,cutoff);
                assertThat(((Number)state.get("currentLoadUnknown")).longValue()).isEqualTo(1);
                assertThat(((Number)state.get("uncompletedLoad")).longValue()).isEqualTo(1);
                jdbc.update("UPDATE platform.payment SET created_at=? WHERE id=(SELECT id FROM platform.payment WHERE id<>? LIMIT 1)",Timestamp.from(fresh),payment);
                assertThat(Pr20PerformanceExperiment.historyUnknownCount(jdbc,cutoff)).isEqualTo(8);
                jdbc.update("UPDATE platform.payment SET resolution_status='KNOWN',unresolved_since=NULL WHERE id=(SELECT id FROM platform.payment WHERE id<>? LIMIT 1)",payment);
                assertThat(Pr20PerformanceExperiment.historyUnknownCount(jdbc,cutoff)).isEqualTo(7);
            }
        }
    }
}
