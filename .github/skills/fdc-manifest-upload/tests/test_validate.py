import os
import stat
import unittest

import helpers
import validate_upload_prerequisites as v


def status(report, name):
    return [x["status"] for x in report["checks"] if x["name"] == name]


class Validate(helpers.Workspace):
    def run_checks(self, **over):
        values = dict(helpers.VALUES)
        values.update(over.pop("values", {}))
        return v.run_checks(values, over.pop("properties", self.props), over.pop("script", self.script), over.pop("cwd", None), over.pop("config", {}))

    def test_everything_valid(self):
        r = self.run_checks()
        self.assertTrue(r["ok"], r["checks"])
        self.assertEqual(r["derived"]["project_root"], self.project)
        self.assertEqual(r["derived"]["cwd"], self.project)
        self.assertEqual(r["derived"]["properties"], self.props)
        self.assertEqual(status(r, "working folder"), [v.PASS])

    def test_empty_environment_and_missing_arguments(self):
        r = self.run_checks(values={"environment": "", "server": None, "release": ""})
        self.assertFalse(r["ok"])
        self.assertEqual(status(r, "environment"), [v.FAIL])
        self.assertEqual(status(r, "argument -s"), [v.FAIL])
        self.assertEqual(status(r, "argument -r"), [v.FAIL])
        self.assertEqual(status(r, "argument -m"), [v.PASS])

    def test_properties_missing_unreadable_or_not_given(self):
        self.assertFalse(self.run_checks(properties=os.path.join(self.root, "nope", "casc.properties"))["ok"])
        self.assertFalse(self.run_checks(properties=None)["ok"])
        self.assertFalse(self.run_checks(properties=self.root)["ok"])          # a folder without casc.properties
        if (not hasattr(os, "geteuid") or os.geteuid() != 0):
            os.chmod(self.props, 0)
            self.assertFalse(self.run_checks()["ok"])

    def test_a_folder_that_holds_casc_properties_is_accepted(self):
        r = self.run_checks(properties=self.project)
        self.assertTrue(r["ok"])
        self.assertEqual(r["derived"]["properties"], self.props)

    def test_script_missing_or_not_executable(self):
        self.assertFalse(self.run_checks(script=os.path.join(self.root, "missing.py"))["ok"])
        os.chmod(self.script, os.stat(self.script).st_mode & ~(stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH))
        if (not hasattr(os, "geteuid") or os.geteuid() != 0):
            self.assertFalse(self.run_checks()["ok"])

    def test_the_real_script_path_is_the_default(self):
        self.assertEqual(v.c.DEFAULT_SCRIPT, "/ms/dist/edap/PROJ/fdc_automation/prod/scripts/casc_upload_manifest.py")

    def test_properties_outside_the_project_root_warn_and_never_move_anything(self):
        os.makedirs(os.path.join(self.project, "conf"))
        moved = os.path.join(self.project, "conf", "casc.properties")
        with open(moved, "w") as f:
            f.write("a=b\n")
        r = self.run_checks(properties=moved)
        self.assertTrue(r["ok"])
        self.assertEqual(status(r, "properties location"), [v.WARN])
        self.assertTrue(os.path.exists(moved) and os.path.exists(self.props))
        self.assertEqual(r["derived"]["cwd"], os.path.join(self.project, "conf"))

    def test_cwd_that_does_not_hold_the_file_warns(self):
        r = self.run_checks(cwd=self.root)
        self.assertEqual(status(r, "working folder"), [v.WARN])

    def test_project_and_release_consistency_only_for_configured_keys(self):
        r = self.run_checks()
        self.assertEqual(status(r, "project consistency"), [v.INFO])
        cfg = {"project_key": "project.name", "release_key": "release.name"}
        self.assertEqual(status(self.run_checks(config=cfg), "project consistency"), [v.PASS])
        bad = self.run_checks(values={"project": "Other"}, config=cfg)
        self.assertFalse(bad["ok"])
        self.assertEqual(status(bad, "project consistency"), [v.FAIL])
        self.assertEqual(status(self.run_checks(config={"project_key": "no.such.key"}), "project consistency"), [v.WARN])

    def test_update_behaviour_is_shown_as_written_and_never_interpreted(self):
        r = self.run_checks(config={"delete_key": "manifest.update.mode"})
        msg = [x["message"] for x in r["checks"] if x["name"] == "update behaviour property"][0]
        self.assertIn("manifest.update.mode=update", msg)
        self.assertIn("not the skill", msg)
        self.assertNotIn("hunter2", str(r))
        unconfigured = [x["message"] for x in self.run_checks()["checks"] if x["name"] == "update behaviour property"][0]
        self.assertIn("cannot say which behaviour applies", unconfigured)

    def test_validation_never_changes_casc_properties(self):
        before = open(self.props).read()
        self.run_checks(config={"delete_key": "manifest.update.mode"})
        self.assertEqual(open(self.props).read(), before)

    def test_inspect_script_is_read_only_and_redacts(self):
        with open(self.script, "a") as f:
            f.write("# reads casc.properties from the cwd, password=hunter2\n")
        lines = v.inspect_script(self.script)
        self.assertTrue(any("casc.properties" in x for x in lines))
        self.assertFalse(any("hunter2" in x for x in lines))

    def test_cli(self):
        code, out = helpers.run_main(v.main, ["--environment", "UAT", "--properties", self.props, "--server", "s", "--meta", "m", "--project", "p", "--release", "1",
                                              "--distribution-system", "d", "--script", self.script])
        self.assertEqual(code, 0, out)
        self.assertIn("all mandatory checks passed", out)
        code, out = helpers.run_main(v.main, ["--environment", "", "--properties", self.props, "--script", self.script])
        self.assertEqual(code, 1)


if __name__ == "__main__":
    unittest.main()
