package com.paymentsledger.reconciliation;

import com.fasterxml.jackson.databind.*;
import com.paymentsledger.provider.ProviderScope;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Validate the actual accepted graph, including its immutable provider observations. */
final class ImportCoverage {
    record Result(boolean complete, String reason, Set<String> ids, List<Map<String,Object>> pages, long failures) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    ImportCoverage(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    Result inspect(Map<String,Object> run) {
        UUID windowId=(UUID)run.get("import_window_id");
        var windows=jdbc.queryForList("SELECT w.*,c.next_cursor,c.api_version,c.version AS cursor_version FROM platform.import_window w LEFT JOIN platform.import_cursor c ON c.window_id=w.id WHERE w.id=?",windowId);
        if(windows.size()!=1)return incomplete("MISSING_IMPORT_WINDOW",List.of());
        var window=windows.getFirst();
        ProviderScope scope=ReconciliationService.scope(run);
        if(!scope.equals(ReconciliationService.scope(window)) || !ReconciliationService.instant(run,"from_at").equals(ReconciliationService.instant(window,"created_gte"))
                || !ReconciliationService.instant(run,"until_at").equals(ReconciliationService.instant(window,"created_lt")))
            return incomplete("IMPORT_WINDOW_SCOPE_MISMATCH",List.of());
        var rows=jdbc.queryForList("""
                SELECT p.*,o.organization_id,o.environment_id,o.provider_account_id,o.livemode,o.import_window_id,o.call_type,o.classification,o.evidence,o.fact
                FROM platform.import_page p JOIN platform.provider_observation o ON o.id=p.observation_id WHERE p.window_id=?
                """,windowId);
        var byCursor=new HashMap<String,Map<String,Object>>();
        for(var row:rows)if(byCursor.put(row.get("request_cursor").toString(),row)!=null)return incomplete("DUPLICATE_REQUEST_CURSOR",rows);
        String cursor="";
        var visited=new HashSet<String>();
        var ids=new LinkedHashSet<String>();
        var pinnedPages=new ArrayList<Map<String,Object>>();
        while(true) {
            if(!visited.add(cursor))return incomplete("CURSOR_CYCLE",pinnedPages);
            var page=byCursor.get(cursor);
            if(page==null)return incomplete("MISSING_ACCEPTED_PAGE",pinnedPages);
            try {
                var evidence=json.readTree(page.get("evidence").toString());
                var fact=json.readTree(page.get("fact").toString());
                String api=window.get("api_version")==null?null:window.get("api_version").toString();
                if(!scope.equals(ReconciliationService.scope(page)) || !windowId.equals(page.get("import_window_id"))
                        || !"LIST_BALANCE_TRANSACTIONS".equals(page.get("call_type")) || !"SUCCESS".equals(page.get("classification"))
                        || api==null || api.isBlank() || !api.equals(evidence.path("apiVersion").asText()))
                    return incomplete("PAGE_SCOPE_OR_API_VERSION_MISMATCH",pinnedPages);
                String[] btIds=(String[])((java.sql.Array)page.get("bt_ids")).getArray();
                if(!fact.path("items").isArray() || btIds.length!=fact.path("items").size())return incomplete("PAGE_FACT_IDS_MISMATCH",pinnedPages);
                for(int i=0;i<btIds.length;i++) {
                    var item=fact.path("items").get(i);
                    Instant created=json.treeToValue(item,com.paymentsledger.provider.ProviderModels.BalanceTransactionFact.class).createdAt();
                    if(!btIds[i].equals(item.path("btId").asText()) || created.isBefore(ReconciliationService.instant(run,"from_at"))
                            || !created.isBefore(ReconciliationService.instant(run,"until_at")))return incomplete("PAGE_FACT_WINDOW_MISMATCH",pinnedPages);
                    if(!ids.add(btIds[i]))return new Result(false,"DUPLICATE_BT_IN_CHAIN",Set.copyOf(ids),List.copyOf(pinnedPages),1);
                }
                boolean terminal=Boolean.TRUE.equals(page.get("terminal"));
                if(terminal==fact.path("hasMore").asBoolean())return incomplete("PAGE_TERMINAL_FACT_MISMATCH",pinnedPages);
                String next=page.get("next_cursor")==null?null:page.get("next_cursor").toString();
                String factNext=fact.path("nextCursor").isNull()||fact.path("nextCursor").isMissingNode()?null:fact.path("nextCursor").asText();
                if(!Objects.equals(next,factNext) || (!terminal&&(btIds.length==0 || !btIds[btIds.length-1].equals(next))))return incomplete("PAGE_CURSOR_FACT_MISMATCH",pinnedPages);
                var pin=new LinkedHashMap<String,Object>();
                pin.put("id",page.get("id").toString());pin.put("observationId",page.get("observation_id").toString());
                pin.put("requestCursor",cursor);pin.put("nextCursor",Objects.toString(next,""));pin.put("terminal",terminal);
                pin.put("btIds",List.of(btIds));pin.put("evidence",evidence);pin.put("fact",fact);pinnedPages.add(pin);
                if(terminal) {
                    if(visited.size()!=rows.size())return incomplete("ORPHAN_ACCEPTED_PAGE",pinnedPages);
                    if(!"COMPLETE".equals(window.get("state")) || window.get("next_cursor")!=null || window.get("completed_at")==null)
                        return incomplete("IMPORT_CURSOR_NOT_TERMINAL",pinnedPages);
                    if(window.get("last_error")!=null)return incomplete("UNRESOLVED_IMPORT_ERROR",pinnedPages);
                    return new Result(true,"COMPLETE_CURSOR_CHAIN",Set.copyOf(ids),List.copyOf(pinnedPages),0);
                }
                cursor=next;
            } catch(Exception invalid) { return incomplete("INVALID_PAGE_EVIDENCE",pinnedPages); }
        }
    }
    private static Result incomplete(String reason,List<Map<String,Object>> pages) {return new Result(false,reason,Set.of(),List.copyOf(pages),0);}
}
