import os
import unittest

import helpers
import run_upload as r


class Upload(helpers.Workspace):
    def plan_hash(self, auth="sso", extra=()):
        code, out = helpers.run_main(r.main, self.argv("plan", ["--auth-method", auth] + list(extra) + ["--json"]))
        self.assertEqual(code, 0, out)
        import json
        return json.loads(out)["plan_hash"]

    def execute(self, auth="sso", confirmed="yes", plan_hash=None, extra=()):
        h = plan_hash or self.plan_hash(auth, [x for x in extra if x != "--ack-unverified-auth"])
        args = ["--auth-method", auth, "--plan-hash", h] + (["--confirmed", confirmed] if confirmed else []) + list(extra)
        return helpers.run_main(r.main, self.argv("execute", args))

    # --- plan / summary
    def test_plan_shows_everything_the_user_must_see(self):
        code, out = helpers.run_main(r.main, self.argv("plan", ["--auth-method", "sso"]))
        self.assertEqual(code, 0)
        for text in ("UAT", "fdc.example.org", "meta1", "MyProject", "1.0", "dist1", self.props, "SSO", "may DELETE existing FDC manifest entries", "Plan hash:",
                     "-s fdc.example.org -m meta1 -p MyProject -r 1.0 -e UAT -md dist1"):
            self.assertIn(text, out)
        self.assertNotIn("hunter2", out)
        self.assertTrue(out.splitlines()[0].strip() == "UPLOAD SUMMARY" or "UPLOAD SUMMARY" in out)

    def test_plan_refuses_when_validation_fails(self):
        argv = self.argv("plan", ["--auth-method", "sso"])
        argv[argv.index("--release") + 1] = ""
        code, out = helpers.run_main(r.main, argv)
        self.assertEqual(code, 2)
        self.assertIn("REFUSED", out)
        self.assertFalse(os.path.exists(self.record))

    def test_proid_plan_needs_a_valid_proid_name(self):
        code, _ = helpers.run_main(r.main, self.argv("plan", ["--auth-method", "proid", "--proid", "bad name;"]))
        self.assertEqual(code, 2)
        code, out = helpers.run_main(r.main, self.argv("plan", ["--auth-method", "proid", "--proid", "svc_app"]))
        self.assertEqual(code, 0)
        self.assertIn("PROID (svc_app)", out)

    # --- confirmation guards
    def test_nothing_runs_without_explicit_confirmation(self):
        code, out = self.execute(confirmed=None)
        self.assertEqual(code, 2)
        code, out = self.execute(confirmed="maybe")
        self.assertEqual(code, 2)
        self.assertFalse(os.path.exists(self.record), "the upload script must not have run")

    def test_a_changed_plan_is_refused(self):
        h = self.plan_hash()
        argv = self.argv("execute", ["--auth-method", "sso", "--plan-hash", h, "--confirmed", "yes"])
        argv[argv.index("--environment") + 1] = "PROD"           # a different environment than the one summarised
        code, out = helpers.run_main(r.main, argv)
        self.assertEqual(code, 2)
        self.assertIn("plan changed", out)
        self.assertFalse(os.path.exists(self.record))

    def test_changed_casc_properties_after_the_summary_is_refused(self):
        h = self.plan_hash()
        with open(self.props, "a") as f:
            f.write("manifest.update.mode=delete\n")
        code, out = self.execute(plan_hash=h)
        self.assertEqual(code, 2)
        self.assertFalse(os.path.exists(self.record))

    def test_unverified_authentication_needs_an_explicit_acknowledgement(self):
        code, out = self.execute(auth="unverified")
        self.assertEqual(code, 2)
        self.assertIn("not verified", out)
        self.assertFalse(os.path.exists(self.record))
        os.environ["FDC_UPLOAD_SUCCESS_PATTERN"] = "UPLOAD OK"
        os.environ["FAKE_OUTPUT"] = "UPLOAD OK\n"
        code, out = self.execute(auth="unverified", extra=("--ack-unverified-auth",))
        self.assertEqual(code, 0, out)
        self.assertIn("NOT VERIFIED by the skill", helpers.run_main(r.main, self.argv("plan", ["--auth-method", "unverified"]))[1])

    # --- execution
    def test_the_existing_script_runs_with_exactly_the_given_arguments_in_the_properties_folder(self):
        os.environ["FDC_UPLOAD_SUCCESS_PATTERN"] = "UPLOAD OK"
        os.environ["FAKE_OUTPUT"] = "UPLOAD OK\n"
        code, out = self.execute()
        self.assertEqual(code, 0, out)
        self.assertIn("UPLOAD SUCCEEDED", out)
        rec = self.recorded()
        self.assertEqual(rec["argv"], ["-s", "fdc.example.org", "-m", "meta1", "-p", "MyProject", "-r", "1.0", "-e", "UAT", "-md", "dist1"])
        self.assertEqual(os.path.realpath(rec["cwd"]), self.project)

    def test_exit_zero_without_proof_is_not_reported_as_success(self):
        os.environ["FAKE_OUTPUT"] = "all done\n"
        code, out = self.execute()
        self.assertEqual(code, 3)
        self.assertIn("NOT VERIFIED", out)
        self.assertNotIn("UPLOAD SUCCEEDED", out)

    def test_failures_are_reported_with_a_category_and_guidance(self):
        os.environ["FAKE_EXIT"] = "1"
        os.environ["FAKE_OUTPUT"] = "ERROR 403 Forbidden for user x password=hunter2\n"
        code, out = self.execute()
        self.assertEqual(code, 1)
        self.assertIn("UPLOAD FAILED", out)
        self.assertIn("Authorization", out)
        self.assertIn("Troubleshooting", out)
        self.assertNotIn("hunter2", out)

    def test_an_error_in_the_output_overrides_exit_code_zero(self):
        os.environ["FAKE_OUTPUT"] = "UPLOAD OK\nTraceback (most recent call last):\nValueError: boom\n"
        os.environ["FDC_UPLOAD_SUCCESS_PATTERN"] = "UPLOAD OK"
        code, out = self.execute()
        self.assertEqual(code, 1)
        self.assertIn("Python execution", out)

    def test_timeout_is_a_failure(self):
        os.environ["FAKE_SLEEP"] = "5"
        code, out = self.execute(extra=("--timeout", "1"))
        self.assertEqual(code, 1)
        self.assertIn("timed out", out)

    def test_values_are_never_interpreted_by_a_shell(self):
        os.environ["FDC_UPLOAD_SUCCESS_PATTERN"] = "x"
        os.environ["FAKE_OUTPUT"] = "x"
        marker = os.path.join(self.project, "PWNED")
        argv = self.argv("plan", ["--auth-method", "sso"])
        argv[argv.index("--server") + 1] = "host; touch PWNED"
        argv[argv.index("--meta") + 1] = "$(touch PWNED)"
        code, out = helpers.run_main(r.main, argv + ["--json"])
        self.assertEqual(code, 0, out)
        import json
        h = json.loads(out)["plan_hash"]
        argv = self.argv("execute", ["--auth-method", "sso", "--plan-hash", h, "--confirmed", "yes"])
        argv[argv.index("--server") + 1] = "host; touch PWNED"
        argv[argv.index("--meta") + 1] = "$(touch PWNED)"
        code, out = helpers.run_main(r.main, argv)
        self.assertEqual(code, 0, out)
        self.assertFalse(os.path.exists(marker))
        self.assertEqual(self.recorded()["argv"][1], "host; touch PWNED")
        self.assertEqual(self.recorded()["argv"][3], "$(touch PWNED)")

    def test_a_value_that_looks_like_an_option_is_refused(self):
        argv = self.argv("plan", ["--auth-method", "sso"])
        i = argv.index("--meta")
        argv[i:i + 2] = ["--meta=-rf"]                      # written this way so that argparse accepts it; the check must refuse it
        code, out = helpers.run_main(r.main, argv)
        self.assertEqual(code, 2)
        self.assertIn("starts with '-'", out)

    def test_casc_properties_is_left_untouched(self):
        before = open(self.props).read()
        os.environ["FAKE_OUTPUT"] = "ok"
        self.execute()
        self.assertEqual(open(self.props).read(), before)

    def test_script_that_cannot_start_is_a_python_execution_failure(self):
        with open(self.script, "w") as f:
            f.write("#!/no/such/interpreter\n")
        code, out = helpers.run_main(r.main, self.argv("plan", ["--auth-method", "sso", "--json"]))
        self.assertEqual(code, 2)        # validation already catches the missing interpreter before anything runs


if __name__ == "__main__":
    unittest.main()
