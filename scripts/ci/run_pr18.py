#!/usr/bin/env python3
"""Run isolated PR18 checks, preserving real counts and failure artifacts. Never read .env."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
import time


PROFILES = {"pr": (100, 100), "nightly": (1000, 300)}
REQUIRED_SCENARIOS = {"competing-refunds", "same-key-refund", "refund-dispute-share", "payout-shared-line"}
REAL_PROBES = ("STRIPE_CONTRACT_TESTS", "PR06_STRIPE_CONTRACT", "PAYOUT_REAL_READ_TESTS")


def budget(profile, seeds, max_steps, seed):
    if profile not in PROFILES:
        raise ValueError("profile must be pr or nightly")
    seeds = PROFILES[profile][0] if seeds is None else seeds
    max_steps = PROFILES[profile][1] if max_steps is None else max_steps
    for value, minimum, maximum, name in [(seeds, 1, 10000, "seeds"), (max_steps, 1, 300, "maxSteps"), (seed, 0, 2**63 - 1, "seed")]:
        if type(value) is not int or not minimum <= value <= maximum:
            raise ValueError(f"{name} must be an integer in {minimum}..{maximum}")
    return seeds, max_steps


def build_command(profile, seeds, max_steps, seed):
    seeds, max_steps = budget(profile, seeds, max_steps, seed)
    return ["./mvnw", "-B", "clean", "verify", f"-Dpr18.seeds={seeds}", f"-Dpr18.maxSteps={max_steps}", f"-Dpr18.seed={seed}"]


def test_environment(environ):
    result = dict(environ)
    for name in REAL_PROBES:
        result[name] = "false"
    # These tests use explicitly wired Fake Providers. No account credentials are needed.
    result.pop("STRIPE_API_KEY", None)
    result.pop("STRIPE_WEBHOOK_SECRET", None)
    return result


def validate_evidence(folder, seeds, max_steps, seed):
    try:
        sequence = json.loads((folder / "sequence-summary.json").read_text())
        concurrency = json.loads((folder / "concurrency-summary.json").read_text())
    except (OSError, ValueError) as failure:
        raise ValueError("PR18 evidence missing or invalid") from failure
    if not isinstance(sequence, dict) or not isinstance(concurrency, dict):
        raise ValueError("PR18 summaries must be JSON objects")
    if not (sequence.get("status") == "PASS" and sequence.get("requestedSeeds") == seeds
            and sequence.get("completedSeeds") == seeds and sequence.get("maxSteps") == max_steps
            and sequence.get("baseSeed") == seed and sequence.get("failedSeeds") == []):
        raise ValueError("PR18 sequence budget was not completed")
    version = sequence.get("generatorVersion")
    if not isinstance(version, str) or not version.strip():
        raise ValueError("generator version is required for reproduction")
    actual_seeds = sequence.get("actualCaseSeeds", [])
    if not isinstance(actual_seeds, list) or any(type(value) is not int for value in actual_seeds) or len(actual_seeds) != seeds or len(set(actual_seeds)) != seeds:
        raise ValueError("case seeds must be recorded and distinct")
    steps = sequence.get("executedSteps", 0)
    operations = sequence.get("operationCounts", {})
    if type(steps) is not int or not seeds <= steps <= seeds * max_steps:
        raise ValueError("actual step count outside requested budget")
    if not isinstance(operations, dict) or not operations or any(type(count) is not int or count < 0 for count in operations.values()) or sum(operations.values()) != steps:
        raise ValueError("operation counts must equal actual executed steps")
    scenarios = concurrency.get("scenarios", [])
    if concurrency.get("status") != "PASS" or not isinstance(scenarios, list) or not scenarios or any(not isinstance(scenario, dict) for scenario in scenarios):
        raise ValueError("concurrency evidence missing or failed")
    names = [scenario.get("name") for scenario in scenarios]
    if any(not isinstance(name, str) for name in names) or set(names) != REQUIRED_SCENARIOS or len(names) != len(REQUIRED_SCENARIOS):
        raise ValueError("all four required concurrency scenarios must appear exactly once")
    for scenario in scenarios:
        pids = scenario.get("backendPids", [])
        if scenario.get("status") != "PASS" or not isinstance(pids, list) or any(type(pid) is not int or pid <= 0 for pid in pids) or len(set(pids)) < 2:
            raise ValueError("concurrency scenario needs distinct real PostgreSQL connections")
    return {"sequence": sequence, "concurrency": concurrency}


def run_process(command, root, environ, log):
    # Maven clean deletes target. Keep the live file outside target and archive after exit.
    with tempfile.TemporaryDirectory(prefix="pr18-run-") as temporary:
        live = Path(temporary) / "maven.txt"
        with live.open("w") as output:
            try:
                child = subprocess.Popen(command, cwd=root, env=environ, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            except OSError as failure:
                output.write(f"Could not start test command: {failure.__class__.__name__}\n")
                code = 127
            else:
                with child.stdout:
                    for line in child.stdout:
                        output.write(line)
                        print(line, end="", flush=True)
                code = child.wait()
        log.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(live, log)
    return code


def input_manifest(root):
    files = [root / "pom.xml", root / "mvnw", root / "mvnw.cmd", root / ".gitignore"]
    for name in ("src", "scripts", ".github", ".mvn"):
        files.extend(path for path in (root / name).rglob("*") if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc")
    return {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(files) if path.is_file()}


def diagnostic(command, root, environ):
    try:
        result = subprocess.run(command, cwd=root, env=environ, capture_output=True, text=True, timeout=20)
        return {"exitCode": result.returncode, "output": (result.stdout + result.stderr).strip()}
    except (OSError, subprocess.TimeoutExpired) as failure:
        return {"status": "UNAVAILABLE", "reason": failure.__class__.__name__}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=PROFILES, default="pr")
    parser.add_argument("--seeds", type=int)
    parser.add_argument("--max-steps", type=int)
    parser.add_argument("--seed", type=int, default=180018)
    args = parser.parse_args(argv)
    try:
        seeds, max_steps = budget(args.profile, args.seeds, args.max_steps, args.seed)
        command = build_command(args.profile, seeds, max_steps, args.seed)
    except ValueError as failure:
        parser.error(str(failure))
    root = Path(__file__).resolve().parents[2]
    folder = root / "target/pr18"
    environ = test_environment(os.environ)
    before = input_manifest(root)
    java = str(Path(environ["JAVA_HOME"]) / "bin/java") if environ.get("JAVA_HOME") else "java"
    report = {"startedAtUtc": datetime.now(timezone.utc).isoformat(), "profile": args.profile,
              "command": command, "budget": {"seeds": seeds, "maxSteps": max_steps, "baseSeed": args.seed},
              "commit": diagnostic(["git", "rev-parse", "HEAD"], root, environ), "environment": {"os": platform.system(), "architecture": platform.machine(), "cpuCount": os.cpu_count(), "python": platform.python_version(), "java": diagnostic([java, "-version"], root, environ), "docker": diagnostic(["docker", "version", "--format", "{{.Server.Version}}"], root, environ)},
              "provider": "FAKE_ONLY", "bankEvidence": "SIMULATED_BANK_EVIDENCE", "realAccountProbesEnabled": False,
              "inputFilesSha256": before}
    started = time.monotonic()
    code = run_process(command, root, environ, folder / "maven.txt")
    report["mavenExitCode"] = code
    report["elapsedSeconds"] = round(time.monotonic() - started, 3)
    report["completedAtUtc"] = datetime.now(timezone.utc).isoformat()
    report["inputDrift"] = before != input_manifest(root)
    report["status"] = "FAIL"
    if code == 0:
        try:
            report["evidence"] = validate_evidence(folder, seeds, max_steps, args.seed)
            if report["inputDrift"]:
                raise ValueError("tested source inputs changed during run")
            report["status"] = "PASS"
        except ValueError as failure:
            report["evidenceFailure"] = str(failure)
            code = 1
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "run-summary.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"PR18 {report['status']}: command exit={report['mavenExitCode']}; elapsed={report['elapsedSeconds']}s; evidence={folder / 'run-summary.json'}")
    return code if code >= 0 else 128 - code


if __name__ == "__main__":
    sys.exit(main())
