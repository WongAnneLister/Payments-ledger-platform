import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stdout


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parents[1] / f"refactor/{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


trace = load("trace_compare")
anchors = load("remap_doc_anchors")


class TraceCompareTest(unittest.TestCase):
    def write(self, root, files):
        for name, lines in files.items():
            path = Path(root) / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("\n".join(lines), encoding="utf-8")

    def compare(self, base, new, *args):
        with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
            self.write(a, base)
            self.write(b, new)
            out = io.StringIO()
            with redirect_stdout(out):
                code = trace.main([a, b, *args])
            return code, out.getvalue()

    def test_masking_hides_labels_digests_and_epoch_seconds_only(self):
        line = 'INSERT x [UUID:U12, Timestamp:@3, String:{"at":1792120286.5,"n":7}, String:' + "a" * 64 + "]"
        self.assertEqual(trace.mask(line), 'INSERT x [UUID:<L>, Timestamp:<L>, String:{"at":<E>,"n":7}, String:<H>]')
        self.assertEqual(trace.mask("UPDATE t SET v=? [Long:12]"), "UPDATE t SET v=? [Long:12]")

    def test_label_renumbering_is_not_a_difference_but_a_changed_value_is(self):
        base = {"PaymentCoreIT/a.txt": ["BEGIN", "SELECT 1 [UUID:U1]", "COMMIT"]}
        relabeled = {"PaymentCoreIT/a.txt": ["BEGIN", "SELECT 1 [UUID:U7]", "COMMIT"]}
        changed = {"PaymentCoreIT/a.txt": ["BEGIN", "SELECT 1 [Long:2]", "COMMIT"]}
        self.assertEqual(self.compare(base, relabeled)[0], 0)
        code, out = self.compare(base, changed)
        self.assertEqual(code, 1)
        self.assertIn("unexpected differences (sql or masked, outside KNOWN_NOISE): 1", out)

    def test_known_noise_and_reordered_statements(self):
        noise = "RefundCoreIT/competingSixHundredReservationsHaveOneWinner.txt"
        code, out = self.compare({noise: ["BEGIN", "ROLLBACK"]}, {noise: ["BEGIN", "COMMIT"]})
        self.assertEqual(code, 0)
        self.assertIn("sql     differing tests: 1", out)
        code, _ = self.compare({"X/a.txt": ["SELECT 1", "SELECT 2"]}, {"X/a.txt": ["SELECT 2", "SELECT 1"]})
        self.assertEqual(code, 1)

    def test_class_summary(self):
        base = {"RefundCoreIT/a.txt": ["SELECT 1"], "RefundCoreIT/b.txt": ["SELECT 2"]}
        new = {"RefundCoreIT/a.txt": ["SELECT 1"], "RefundCoreIT/b.txt": ["SELECT 3"]}
        _, out = self.compare(base, new, "--classes", "RefundCoreIT")
        self.assertIn("RefundCoreIT: 1/2 differ ['b.txt']", out)


class RemapDocAnchorsTest(unittest.TestCase):
    OLD = ["class A {", "    void a() {", "        x();", "    }", "}"]
    NEW = ["class A {", "    // moved down", "", "    void a() {", "        x();", "    }", "}"]

    def remap(self, text, renames=()):
        return anchors.remap(
            text,
            "docs/english/x.md",
            {"src/A.java"},
            lambda path: self.OLD,
            lambda path: self.NEW,
            renames,
        )

    def test_moves_links_and_their_line_numbers_in_the_label(self):
        text, moved, manual = self.remap("see [A.java:2](../../src/A.java#L2) and [line 3](../../src/A.java#L3)")
        self.assertEqual(text, "see [A.java:4](../../src/A.java#L4) and [line 5](../../src/A.java#L5)")
        self.assertEqual((moved, manual), (2, []))

    def test_ambiguous_missing_and_unchanged_files_are_left_alone(self):
        text, moved, manual = self.remap("[end](../../src/A.java#L4) [gone](../../src/A.java#L9)")
        self.assertEqual(text, "[end](../../src/A.java#L4) [gone](../../src/A.java#L9)")
        self.assertEqual(moved, 0)
        self.assertEqual(len(manual), 2)
        self.assertIn("(2 matches)", manual[0])
        untouched = anchors.remap("[b](../../src/B.java#L2)", "docs/english/x.md", {"src/A.java"}, None, None)
        self.assertEqual(untouched, ("[b](../../src/B.java#L2)", 0, []))

    def test_renames_and_whitespace_do_not_prevent_a_match(self):
        self.assertEqual(anchors.relocate("x( a ,b );", ["  x(a,b);"]), [1])
        self.assertEqual(anchors.relocate("one(c)", ["two(c)"], [("one", "two")]), [1])
        self.assertEqual(anchors.relocate("   ", ["", " "]), [])


if __name__ == "__main__":
    unittest.main()
