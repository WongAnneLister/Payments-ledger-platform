"""Compare two SqlTrace trees (target/failsafe-reports/sql-trace) recorded before and after a refactor.

Each test's trace is compared at three strictness levels:

  full    exact trace lines
  sql     statements and transaction boundaries only (parameters dropped)
  masked  full lines with SqlTrace labels (U1, T2, ID3, @4), 64-hex digests and epoch-second JSON numbers masked

A behaviour-preserving refactor should leave the sql and masked levels unchanged except for KNOWN_NOISE.

usage: trace_compare.py <base-dir> <new-dir> [--show N] [--classes Class...]
"""
import argparse
import os
import re

LABEL = re.compile(r'\b(?:U|T|ID)\d+\b|@\d+')
DIGEST = re.compile(r'\b[0-9a-f]{64}\b')
EPOCH = re.compile(r'(?<=":)(?:1[6-9]\d{8}(?:\.\d+)?|1\.\d+E\+9)')

# Tests whose traces legitimately differ between runs: thread races, arrays ordered by random UUIDs, ties broken by
# random ids. ReconciliationRecoveryIT also shifts entirely when the two runs straddle a UTC date change.
KNOWN_NOISE = (
    'DisputeLifecycleIT/failingKnownSourceDoesNotStarveAnotherDispute',
    'OperationRecoveryIT/twoWorkersCannotBothOwnOneExpiredOperation',
    'OperationRecoveryIT/persistentAccountLimitAppliesAcrossCoordinatorInstances',
    'RefundCoreIT/competingSixHundredReservationsHaveOneWinner',
    'ReconciliationRecoveryIT/',
)


def load(root):
    traces = {}
    for directory, _, files in os.walk(root):
        for name in files:
            path = os.path.join(directory, name)
            key = os.path.relpath(path, root).replace(os.sep, '/')
            with open(path, encoding='utf-8') as trace:
                traces[key] = trace.read().split('\n')
    return traces


def mask(line):
    return EPOCH.sub('<E>', DIGEST.sub('<H>', LABEL.sub('<L>', line)))


def level(lines, name):
    if name == 'full':
        return lines
    if name == 'sql':
        return [re.sub(r' \[.*$', '', line) for line in lines]
    return [mask(line) for line in lines]


def known_noise(test):
    return any(test.startswith(prefix) for prefix in KNOWN_NOISE)


def differing(base, new, name):
    return [test for test in sorted(set(base) & set(new)) if level(base[test], name) != level(new[test], name)]


def first_difference(a, b):
    for x, y in zip(a, b):
        if x != y:
            i = next((k for k in range(min(len(x), len(y))) if x[k] != y[k]), min(len(x), len(y)))
            return f'base: ...{x[max(0, i - 70):i + 90]}\n      new:  ...{y[max(0, i - 70):i + 90]}'
    return f'lengths {len(a)} vs {len(b)}'


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('base')
    parser.add_argument('new')
    parser.add_argument('--show', type=int, default=0, help='print the first difference of up to N tests per level')
    parser.add_argument('--classes', nargs='*', default=[], help='also summarize these test classes (masked level)')
    args = parser.parse_args(argv)

    base, new = load(args.base), load(args.new)
    common = sorted(set(base) & set(new))
    print(f'tests: base={len(base)} new={len(new)} common={len(common)} '
          f'only-base={len(set(base) - set(new))} only-new={len(set(new) - set(base))}')
    print(f'statements in common tests: {sum(len(base[test]) for test in common)}')
    unexpected = []
    for name in ('full', 'sql', 'masked'):
        tests = differing(base, new, name)
        print(f'{name:7} differing tests: {len(tests)}')
        for test in tests[:args.show]:
            print(f'    {test}\n      {first_difference(level(base[test], name), level(new[test], name))}')
        if name != 'full':
            unexpected += [test for test in tests if not known_noise(test) and test not in unexpected]
    for cls in args.classes:
        tests = [test for test in common if test.startswith(cls + '/')]
        changed = [test.split('/', 1)[1] for test in differing({t: base[t] for t in tests}, new, 'masked')]
        print(f'{cls}: {len(changed)}/{len(tests)} differ {changed}')
    print(f'unexpected differences (sql or masked, outside KNOWN_NOISE): {len(unexpected)}')
    for test in unexpected:
        print(f'    {test}')
    return 1 if unexpected else 0


if __name__ == '__main__':
    raise SystemExit(main())
