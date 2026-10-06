# Task1 independent specification review

Scope: PR17 UI, base fc09cb2, working-tree implementation including untracked assets; parent docs/browser helper and Task2 backend excluded.

Initial verdict: FAIL. Reviewer reproduced actual modules independently:

1. Customer POST replay displayed a terminal response without current original-payment GET.
2. Verifying unchanged ops credentials rebuilt controls for an uncertain bank command, retaining POST bytes/key but routing the subsequent GET by the changed selector.
3. Explicit WAIT_FOR_CONFIRMATION next action could coexist with a green terminal presentation.

Tests were added and expected assertions observed RED before each fix. Customer replay now retains metadata and GETs original identity before presentation; pending ops context binds kind/target and survives unchanged identity while changed trusted role/scope clears it; explicit non-NONE action suppresses terminal success.

Final independent verdict: PASS. Reviewer inspected the actual fixes and independently ran 34/34 Node tests. No edits or Maven runs by reviewer. Parent separately ran the same 34 tests; full Java verification remains a delivery gate.
