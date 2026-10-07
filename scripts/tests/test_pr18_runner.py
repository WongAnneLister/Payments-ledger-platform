import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("pr18_runner", Path(__file__).parents[1] / "ci/run_pr18.py")
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class Pr18RunnerTest(unittest.TestCase):
    def test_default_and_nightly_commands_keep_explicit_budgets(self):
        self.assertEqual(runner.build_command("pr", None, None, 180018),
                         ["./mvnw", "-B", "clean", "verify", "-Dpr18.seeds=100", "-Dpr18.maxSteps=100", "-Dpr18.seed=180018"])
        self.assertIn("-Dpr18.seeds=1000", runner.build_command("nightly", None, None, 5))
        self.assertIn("-Dpr18.maxSteps=300", runner.build_command("nightly", None, None, 5))

    def test_invalid_numeric_ranges_fail_before_starting_maven(self):
        for seeds, steps in [(0, 100), (-1, 100), (10001, 100), (100, 0), (100, 301), (True, 100)]:
            with self.subTest(seeds=seeds, steps=steps), self.assertRaises(ValueError):
                runner.build_command("pr", seeds, steps, 1)
        with self.assertRaises(ValueError):
            runner.build_command("unknown", None, None, 1)

    def test_real_account_probes_are_disabled_even_if_inherited(self):
        original = {"PATH": "/fixture/bin", "STRIPE_CONTRACT_TESTS": "true", "PR06_STRIPE_CONTRACT": "true", "PAYOUT_REAL_READ_TESTS": "true"}
        actual = runner.test_environment(original)
        self.assertEqual(actual["PATH"], original["PATH"])
        self.assertEqual(actual["STRIPE_CONTRACT_TESTS"], "false")
        self.assertEqual(actual["PR06_STRIPE_CONTRACT"], "false")
        self.assertEqual(actual["PAYOUT_REAL_READ_TESTS"], "false")
        self.assertEqual(original["STRIPE_CONTRACT_TESTS"], "true")

    def evidence(self, folder, **changes):
        summary = {"status": "PASS", "baseSeed": 180018, "requestedSeeds": 100, "completedSeeds": 100,
                   "maxSteps": 100, "executedSteps": 1000, "operationCounts": {"RESERVE": 1000},
                   "failedSeeds": [], "actualCaseSeeds": list(range(100)), "generatorVersion": "PR18-1"}
        summary.update(changes)
        (folder / "sequence-summary.json").write_text(json.dumps(summary))
        (folder / "concurrency-summary.json").write_text(json.dumps({"status": "PASS", "scenarios": [
            {"name": name, "status": "PASS", "backendPids": [10, 11]}
            for name in ["competing-refunds", "same-key-refund", "refund-dispute-share", "payout-shared-line"]]}))

    def test_missing_or_short_evidence_never_becomes_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            with self.assertRaises(ValueError):
                runner.validate_evidence(folder, 100, 100, 180018)
            for changes in [{"completedSeeds": 99}, {"requestedSeeds": 99}, {"failedSeeds": [42]}, {"maxSteps": 99}, {"generatorVersion": None}, {"generatorVersion": ""}, {"baseSeed": 1}, {"executedSteps": 0}, {"actualCaseSeeds": [1] * 100}]:
                self.evidence(folder, **changes)
                with self.subTest(changes=changes), self.assertRaises(ValueError):
                    runner.validate_evidence(folder, 100, 100, 180018)

    def test_accepts_actual_evidence_and_rejects_thread_only_claims(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            self.evidence(folder)
            result = runner.validate_evidence(folder, 100, 100, 180018)
            self.assertEqual(result["sequence"]["executedSteps"], 1000)
            (folder / "concurrency-summary.json").write_text(json.dumps({"status": "PASS", "scenarios": [{"name": "race", "status": "PASS", "backendPids": [10, 10]}]}))
            with self.assertRaises(ValueError):
                runner.validate_evidence(folder, 100, 100, 180018)

    def test_every_required_concurrency_scenario_is_present_exactly_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            self.evidence(folder)
            complete = json.loads((folder / "concurrency-summary.json").read_text())
            for scenarios in [complete["scenarios"][:1], complete["scenarios"][:-1] + [complete["scenarios"][0]]]:
                (folder / "concurrency-summary.json").write_text(json.dumps({"status": "PASS", "scenarios": scenarios}))
                with self.subTest(scenarios=scenarios), self.assertRaises(ValueError):
                    runner.validate_evidence(folder, 100, 100, 180018)

    def test_malformed_json_shapes_are_rejected_as_invalid_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            for filename, broken in [("sequence-summary.json", []), ("concurrency-summary.json", []),
                                     ("sequence-summary.json", {"operationCounts": []}),
                                     ("sequence-summary.json", {"actualCaseSeeds": [[1]] * 100}),
                                     ("concurrency-summary.json", {"status": "PASS", "scenarios": [None]})]:
                self.evidence(folder)
                if filename == "sequence-summary.json" and isinstance(broken, dict):
                    original = json.loads((folder / filename).read_text())
                    original.update(broken)
                    broken = original
                (folder / filename).write_text(json.dumps(broken))
                with self.subTest(filename=filename, broken=broken), self.assertRaises(ValueError):
                    runner.validate_evidence(folder, 100, 100, 180018)

    def test_malformed_evidence_still_writes_fail_run_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            def fake_process(command, repository, environ, log):
                log.parent.mkdir(parents=True)
                log.write_text("MAVEN_FIXTURE_EXIT_ZERO\n")
                (log.parent / "sequence-summary.json").write_text("[]")
                (log.parent / "concurrency-summary.json").write_text("[]")
                return 0
            with patch.object(runner, "__file__", str(root / "scripts/ci/run_pr18.py")), patch.object(runner, "diagnostic", return_value={"source": "UNIT_FIXTURE"}), patch.object(runner, "run_process", side_effect=fake_process):
                self.assertEqual(runner.main([]), 1)
            summary = json.loads((root / "target/pr18/run-summary.json").read_text())
            self.assertEqual(summary["status"], "FAIL")
            self.assertEqual(summary["mavenExitCode"], 0)
            self.assertEqual(summary["budget"]["seeds"], 100)
            self.assertIn("elapsedSeconds", summary)
            self.assertIn("evidenceFailure", summary)

    def test_child_failure_is_preserved_and_output_is_retained(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            code = runner.run_process(["/bin/sh", "-c", "echo deliberate-runner-fixture; exit 17"], folder, runner.test_environment({"PATH": "/usr/bin:/bin"}), folder / "run.txt")
            self.assertEqual(code, 17)
            self.assertIn("deliberate-runner-fixture", (folder / "run.txt").read_text())


if __name__ == "__main__":
    unittest.main()
