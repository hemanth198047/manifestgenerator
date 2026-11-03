import os
import unittest

import helpers  # noqa: F401  (sets sys.path)
import fdc_common as c


class ValueChecks(unittest.TestCase):
    def test_environment(self):
        self.assertIsNone(c.check_environment("UAT"))
        self.assertIsNone(c.check_environment("prod-eu.1"))
        for bad in (None, "", "   ", "UAT; rm -rf /", "UAT PROD", "-e", "a" * 65, "UAT\n"):
            self.assertIsNotNone(c.check_environment(bad), repr(bad))

    def test_arguments(self):
        self.assertIsNone(c.check_argument("Server", "https://fdc.example.org:8443/x"))
        self.assertIsNone(c.check_argument("Project", "My Project"))
        for bad in (None, "", " x", "x ", "-rf", "a\nb", "a\x00b", "a" * 257):
            self.assertIsNotNone(c.check_argument("Server", bad), repr(bad))

    def test_proid_is_a_name_not_a_secret(self):
        self.assertIsNone(c.check_proid("proid_app-1"))
        for bad in (None, "", "p roid", "pw;rm", "x" * 80):
            self.assertIsNotNone(c.check_proid(bad), repr(bad))


class Command(unittest.TestCase):
    def test_command_is_the_given_script_with_the_given_flags_in_order(self):
        values = dict(server="S", meta="M", project="P", release="R", environment="E", distribution_system="D")
        self.assertEqual(c.build_command(c.DEFAULT_SCRIPT, values),
                         ["/ms/dist/edap/PROJ/fdc_automation/prod/scripts/casc_upload_manifest.py", "-s", "S", "-m", "M", "-p", "P", "-r", "R", "-e", "E", "-md", "D"])

    def test_values_with_shell_characters_stay_one_argument(self):
        values = dict(server="a; touch x", meta="$(id)", project="p`id`", release="1 2", environment="E", distribution_system="d|e")
        argv = c.build_command("script", values)
        self.assertEqual(len(argv), 13)
        self.assertEqual(argv[2], "a; touch x")

    def test_plan_hash_changes_with_anything_that_matters(self):
        base = c.plan_hash(["s", "-e", "UAT"], "/p", "/p/casc.properties", "aa", "sso")
        self.assertEqual(base, c.plan_hash(["s", "-e", "UAT"], "/p", "/p/casc.properties", "aa", "sso"))
        for other in (c.plan_hash(["s", "-e", "PROD"], "/p", "/p/casc.properties", "aa", "sso"), c.plan_hash(["s", "-e", "UAT"], "/q", "/p/casc.properties", "aa", "sso"),
                      c.plan_hash(["s", "-e", "UAT"], "/p", "/p/casc.properties", "bb", "sso"), c.plan_hash(["s", "-e", "UAT"], "/p", "/p/casc.properties", "aa", "proid")):
            self.assertNotEqual(base, other)

    def test_template_placeholders_cannot_add_arguments(self):
        argv = c.split_template("tool --env {environment} --who {proid}", {"environment": "UAT", "proid": "a b; rm -rf /"})
        self.assertEqual(argv, ["tool", "--env", "UAT", "--who", "a b; rm -rf /"])
        with self.assertRaises(ValueError):
            c.split_template("   ", {})


class Properties(unittest.TestCase):
    TEXT = "# comment\n! also comment\nproject.name = MyProject\nrelease: 1.0\nmanifest.update.mode=update\nlong=a\\\n  b\nservice.password=hunter2\nflag\n"

    def test_parse(self):
        entries = {k: v for k, v, _ in c.parse_properties(self.TEXT)}
        self.assertEqual(entries["project.name"], "MyProject")
        self.assertEqual(entries["release"], "1.0")
        self.assertEqual(entries["long"], "ab")
        self.assertEqual(entries["flag"], "")
        self.assertNotIn("# comment", entries)

    def test_describe_does_not_interpret_and_hides_secrets(self):
        info = c.describe_properties(c.parse_properties(self.TEXT), delete_key="manifest.update.mode", project_key="project.name")
        self.assertEqual(info["behaviour_property"], {"key": "manifest.update.mode", "present": True, "value": "update"})
        self.assertEqual(info["project_value"], "MyProject")
        shown = " ".join("%s=%s" % (x["key"], x["value"]) for x in info["candidate_lines"])
        self.assertNotIn("hunter2", shown)
        info2 = c.describe_properties(c.parse_properties("update.password=secret1\nupdate.mode=x"))
        self.assertIn("<hidden>", [x["value"] for x in info2["candidate_lines"]])

    def test_project_root(self):
        import tempfile
        with tempfile.TemporaryDirectory() as t:
            t = os.path.realpath(t)
            os.makedirs(os.path.join(t, "proj", ".git"))
            os.makedirs(os.path.join(t, "proj", "conf"))
            open(os.path.join(t, "proj", "conf", "casc.properties"), "w").close()
            root, marker = c.find_project_root(os.path.join(t, "proj", "conf", "casc.properties"))
            self.assertEqual((root, marker), (os.path.join(t, "proj"), ".git"))
            os.makedirs(os.path.join(t, "plain"))
            open(os.path.join(t, "plain", "casc.properties"), "w").close()
            root, marker = c.find_project_root(os.path.join(t, "plain", "casc.properties"))
            self.assertEqual(root, os.path.join(t, "plain"))
            self.assertIsNone(marker)


class Secrets(unittest.TestCase):
    def test_redact(self):
        text = "password=hunter2 token: abc123 Authorization: Bearer abcdefghijkl api_key='k1' ok=fine"
        out = c.redact(text)
        for secret in ("hunter2", "abc123", "abcdefghijkl", "k1"):
            self.assertNotIn(secret, out)
        self.assertIn("ok=fine", out)


class Results(unittest.TestCase):
    def test_success_needs_proof(self):
        self.assertEqual(c.classify_output("Upload OK", 0, "Upload OK")["result"], "SUCCEEDED")
        self.assertEqual(c.classify_output("Upload OK", 0, None)["result"], "UNVERIFIED")
        self.assertEqual(c.classify_output("done", 0, "Upload OK")["result"], "UNVERIFIED")
        self.assertEqual(c.classify_output("", 0, None)["result"], "UNVERIFIED")

    def test_failures_are_never_success(self):
        self.assertEqual(c.classify_output("x", 1, "x")["result"], "FAILED")
        self.assertEqual(c.classify_output("Upload OK\nERROR: 403 Forbidden", 0, "Upload OK")["result"], "FAILED")
        self.assertEqual(c.classify_output("", 0, "x", timed_out=True)["result"], "FAILED")

    def test_categories(self):
        cases = {"401 Unauthorized": "Authentication", "403 Forbidden": "Authorization", "Unknown environment XYZ": "Invalid environment",
                 "casc.properties not found": "Missing configuration", "usage: x\nerror: the following arguments are required: -s": "Invalid script arguments",
                 "Traceback (most recent call last):": "Python execution", "Connection refused": "FDC connectivity", "Manifest validation failed": "Manifest validation"}
        for text, cat in cases.items():
            self.assertIn(cat, c.classify_output(text, 1)["categories"], text)
        self.assertEqual(c.classify_output("boom", 1)["categories"], ["Other script error"])


if __name__ == "__main__":
    unittest.main()
