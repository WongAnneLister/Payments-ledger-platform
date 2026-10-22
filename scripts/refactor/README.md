# Refactoring tools

Tools for behaviour-preserving refactors, used to split modules into domain / repository / service layers
(see [the roadmap](../../docs/superpowers/plans/2026-10-22-code-quality-roadmap.md)).

## Verifying a refactor with SQL traces

Tests whose fixture wraps its DataSource with `SqlTrace` (for example `PaymentCoreFixture`) write every statement,
transaction boundary and parameter to `target/failsafe-reports/sql-trace/<TestClass>/<test>.txt`. CI uploads this
directory in the `test-reports-jdk21` artifact.

1. Push characterization tests with production code unchanged and let CI record the baseline.
2. Push the refactor on the same UTC day (reconciliation tests shift their business dates otherwise).
3. Download both artifacts and compare:

```bash
gh run download <baseline-run> -n test-reports-jdk21 -D /tmp/base
gh run download <refactor-run> -n test-reports-jdk21 -D /tmp/new
python scripts/refactor/trace_compare.py /tmp/base/failsafe-reports/sql-trace /tmp/new/failsafe-reports/sql-trace --show 10 --classes RefundCoreIT
```

The `sql` level must match apart from `KNOWN_NOISE` (races, random-id ordering). The `masked` level hides SqlTrace
labels, digests and epoch seconds, so it should match as well. The exit code is 1 when anything else differs. The
`full` level differs in almost every test between runs and is informational.

## Keeping documentation line links valid

Documentation links such as `PaymentRepository.java#L338` point at a line. After code moves, run:

```bash
python scripts/refactor/remap_doc_anchors.py HEAD -- $(git grep -lE '\.java#L[0-9]+' -- '*.md')
```

Links whose line still exists exactly once are moved. Links listed for manual review usually point at code that moved
to another class and must be retargeted by hand. `python scripts/ci/check_doc_links.py` then checks every link.
