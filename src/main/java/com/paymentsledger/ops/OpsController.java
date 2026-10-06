package com.paymentsledger.ops;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.identity.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("api")
@RequestMapping("/ops")
public class OpsController {
  private final OpsQueryService queries;
  private final OpsCommandService commands;
  private final OpsAuditService audit;

  public OpsController(OpsQueryService queries, OpsCommandService commands, OpsAuditService audit) {
    this.queries = queries;
    this.commands = commands;
    this.audit = audit;
  }

  @GetMapping({
    "/payments/{id}",
    "/refunds/{id}",
    "/disputes/{id}",
    "/payouts/{id}",
    "/revenue-shares/{id}",
    "/reconciliation-runs/{id}",
    "/adjustments/{id}"
  })
  public JsonNode detail(@PathVariable String id, HttpServletRequest request) {
    String[] parts = request.getRequestURI().split("/");
    return correlated(queries.detail(
        AuthenticationFilter.ops(request),
        parts[parts.length - 2],
        OpsAuthorization.resourceId(
            id,
            switch (parts[parts.length - 2]) {
              case "payments", "revenue-shares" -> "pay";
              case "refunds" -> "ref";
              case "disputes" -> "dis";
              case "payouts" -> "po";
              case "adjustments" -> "adj";
              default -> "run";
            })));
  }

  @GetMapping("/ledger/journals/{id}")
  public JsonNode journal(@PathVariable String id, HttpServletRequest request) {
    return correlated(queries.detail(
        AuthenticationFilter.ops(request), "journals", OpsAuthorization.resourceId(id, "jrnl")));
  }

  @GetMapping({"/ledger/journals", "/balances", "/exceptions", "/tasks"})
  public JsonNode list(@RequestParam Map<String, String> params, HttpServletRequest request) {
    String[] parts = request.getRequestURI().split("/");
    return correlated(queries.list(AuthenticationFilter.ops(request), parts[parts.length - 1], params));
  }

  @PostMapping("/reconciliation-runs")
  public ResponseEntity<JsonNode> reconcile(
      @RequestBody JsonNode b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest r) {
    return command(r, "CREATE_RECONCILIATION", null, key, b, 202);
  }

  @PostMapping("/bank-statements/import")
  public ResponseEntity<JsonNode> bank(
      @RequestBody JsonNode b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest r) {
    return command(r, "IMPORT_BANK", null, key, b, 201);
  }

  @PostMapping("/exceptions/{id}/actions")
  public ResponseEntity<JsonNode> exception(
      @PathVariable String id,
      @RequestBody JsonNode b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest r) {
    return command(r, "EXCEPTION_ACTION", OpsAuthorization.resourceId(id, "exc"), key, b, 200);
  }

  @PostMapping("/tasks/{id}/retry")
  public ResponseEntity<JsonNode> retry(
      @PathVariable String id,
      @RequestBody JsonNode b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest r) {
    return command(r, "RETRY_TASK", OpsAuthorization.resourceId(id, "task"), key, b, 200);
  }

  @PostMapping("/adjustments")
  public ResponseEntity<JsonNode> adjustment(
      @RequestBody JsonNode b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest r) {
    return command(r, "CREATE_ADJUSTMENT", null, key, b, 201);
  }

  @PostMapping("/adjustments/{id}/approve")
  public ResponseEntity<JsonNode> approve(
      @PathVariable String id,
      @RequestBody JsonNode b,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest r) {
    return command(r, "APPROVE_ADJUSTMENT", OpsAuthorization.resourceId(id, "adj"), key, b, 200);
  }

  private JsonNode correlated(JsonNode result) {
    com.paymentsledger.observability.CorrelationLog.read("ops.read", result);
    return result;
  }

  private ResponseEntity<JsonNode> command(
      HttpServletRequest request, String action, UUID target, String key, JsonNode b, int status) {
    var c = AuthenticationFilter.ops(request);
    String t = Objects.toString(target, "collection");
    try {
      var result = commands.execute(c, action, target, key, b);
      request.setAttribute("ops.audited", true);
      var body = (ObjectNode) result.body().deepCopy();
      com.paymentsledger.observability.CorrelationLog.read("ops.command", body);
      var headers = new HttpHeaders();
      if (result.replay()) headers.set("Idempotent-Replay", "true");
      return ResponseEntity.status(
              result.replay() || action.equals("IMPORT_BANK") && body.path("duplicate").asBoolean()
                  ? 200
                  : status)
          .headers(headers)
          .body(body);
    } catch (RuntimeException e) {
      audit.record(
          c,
          action,
          t,
          b,
          e instanceof ApiException api && api.errorCode().httpStatus().value() == 403
              ? "DENIED"
              : "REJECTED");
      request.setAttribute("ops.audited", true);
      throw e;
    }
  }
}
