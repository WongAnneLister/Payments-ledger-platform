#!/usr/bin/env python3
"""校验 docs/contracts/openapi.yaml。

把 PR00 手工做过的检查固化为可重复执行的门槛：
  1. YAML 可解析
  2. 所有 $ref 可解析（无悬空引用）
  3. 首批接口存在且**不是占位**（有 security、响应、必要的 requestBody）
  4. Error schema 必含 code / message / retryable / requestId
  5. 关键请求/响应模型的 properties 非空

退出码非 0 表示契约退化，CI 失败。
"""
from __future__ import annotations

import sys
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
SPEC = REPO_ROOT / "docs" / "contracts" / "openapi.yaml"

BATCH_ONE = [
    ("/v1/identity", "get", False),
    ("/v1/applications/{id}", "get", False),
    ("/v1/prices/{id}", "get", False),
    ("/ops/identity", "get", False),
    ("/v1/payments", "post", True),
    ("/v1/payments/{paymentId}", "get", False),
    ("/v1/payments/{paymentId}/cancel", "post", False),
    ("/v1/refunds", "post", True),
    ("/v1/refunds/{refundId}", "get", False),
    ("/webhooks/stripe", "post", True),
]

NON_EMPTY_SCHEMAS = [
    "TenantContext",
    "OpsIdentity",
    "ControlledPrice",
    "PaymentCreateRequest",
    "PaymentResource",
    "RefundCreateRequest",
    "RefundResource",
    "StripeEventEnvelope",
    "Error",
]

MANDATORY_ERROR_FIELDS = {"code", "message", "retryable", "requestId"}


def collect_refs(node, out):
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "$ref" and isinstance(value, str):
                out.add(value)
            else:
                collect_refs(value, out)
    elif isinstance(node, list):
        for item in node:
            collect_refs(item, out)


def resolve(doc, ref: str) -> bool:
    if not ref.startswith("#/"):
        return False
    node = doc
    for part in ref[2:].split("/"):
        part = part.replace("~1", "/").replace("~0", "~")
        if not isinstance(node, dict) or part not in node:
            return False
        node = node[part]
    return True


def main() -> int:
    failures: list[str] = []

    if not SPEC.exists():
        print(f"FAIL: missing {SPEC.relative_to(REPO_ROOT)}")
        return 1

    try:
        doc = yaml.safe_load(SPEC.read_text(encoding="utf-8"))
    except yaml.YAMLError as exc:
        print(f"FAIL: OpenAPI does not parse: {exc}")
        return 1

    paths = doc.get("paths") or {}
    schemas = (doc.get("components") or {}).get("schemas") or {}
    print(f"parsed: paths={len(paths)} schemas={len(schemas)}")

    refs: set[str] = set()
    collect_refs(doc, refs)
    unresolved = sorted(r for r in refs if not resolve(doc, r))
    print(f"$ref: total={len(refs)} unresolved={len(unresolved)}")
    failures.extend(f"unresolved $ref: {r}" for r in unresolved)

    for path, method, needs_body in BATCH_ONE:
        operation = (paths.get(path) or {}).get(method)
        if not operation:
            failures.append(f"missing batch-1 operation: {method.upper()} {path}")
            continue
        responses = operation.get("responses") or {}
        if len(responses) < 2:
            failures.append(f"placeholder-looking operation (responses<2): {method.upper()} {path}")
        if "security" not in operation:
            failures.append(f"operation without explicit security: {method.upper()} {path}")
        if needs_body and not operation.get("requestBody"):
            failures.append(f"operation missing requestBody: {method.upper()} {path}")

    error_required = set((schemas.get("Error") or {}).get("required") or [])
    missing_error_fields = MANDATORY_ERROR_FIELDS - error_required
    if missing_error_fields:
        failures.append(f"Error schema missing required fields: {sorted(missing_error_fields)}")

    for name in NON_EMPTY_SCHEMAS:
        schema = schemas.get(name)
        if not schema:
            failures.append(f"missing schema: {name}")
        elif not schema.get("properties"):
            failures.append(f"schema has no properties: {name}")

    if failures:
        print(f"\nFAIL ({len(failures)} problem(s)):")
        for item in failures:
            print(f"  - {item}")
        return 1

    print("\nPASS: OpenAPI contract checks")
    return 0


if __name__ == "__main__":
    sys.exit(main())
