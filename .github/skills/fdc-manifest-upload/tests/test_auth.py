import sys
import unittest

import helpers
import auth_check as a

PY = sys.executable


def cmd(code, text=""):
    """A command template that exits with the given code (optionally printing text first)."""
    script = "import sys; sys.stdout.write(%r); sys.exit(%d)" % (text, code)
    return "%s -c \"%s\"" % (PY, script.replace('"', '\\"'))


class Auth(helpers.Workspace):
    def test_nothing_configured_verifies_nothing(self):
        for method, extra in (("sso", {}), ("proid", {"proid": "svc_app"})):
            r = a.check(method, "UAT", "P", "1.0", config={}, **extra)
            self.assertEqual(r["code"], a.NOT_CONFIGURED, r)
            self.assertNotEqual(r["authentication"], "verified")
            self.assertNotEqual(r["authorization"], "verified")

    def test_sso_active_and_authorized(self):
        r = a.check("sso", "UAT", "P", "1.0", config={"sso_status": cmd(0), "authz": cmd(0)})
        self.assertEqual((r["code"], r["authentication"], r["authorization"]), (0, "verified", "verified"))

    def test_sso_not_active_shows_the_login_command_and_does_not_run_it(self):
        marker = self.root + "/login-ran"
        login = "%s -c \"open('%s','w')\" {environment}" % (PY, marker)
        r = a.check("sso", "UAT", config={"sso_status": cmd(1), "sso_login": login, "authz": cmd(0)})
        self.assertEqual(r["code"], a.NOT_ACTIVE)
        self.assertIn("UAT", r["login_command"])
        import os
        self.assertFalse(os.path.exists(marker))

    def test_authentication_is_not_authorization(self):
        r = a.check("sso", "PROD", config={"sso_status": cmd(0), "authz": cmd(1, "denied")})
        self.assertEqual((r["code"], r["authentication"], r["authorization"]), (a.AUTHZ_FAILED, "verified", "failed"))
        missing_authz = a.check("sso", "PROD", config={"sso_status": cmd(0)})
        self.assertEqual(missing_authz["code"], a.NOT_CONFIGURED)
        self.assertNotEqual(missing_authz["authorization"], "verified")

    def test_proid_flow(self):
        cfg = {"proid_auth": cmd(0), "authz": cmd(0)}
        self.assertEqual(a.check("proid", "UAT", proid="svc_app", config=cfg)["code"], 0)
        self.assertEqual(a.check("proid", "UAT", proid="svc_app", config={"proid_auth": cmd(1), "authz": cmd(0)})["code"], a.AUTHN_FAILED)
        self.assertEqual(a.check("proid", "UAT", proid="svc_app", config={"proid_auth": cmd(0), "authz": cmd(1)})["code"], a.AUTHZ_FAILED)

    def test_proid_must_be_a_name_never_a_password(self):
        for bad in (None, "", "pa ss", "x;rm -rf /", "p@ss"):
            self.assertEqual(a.check("proid", "UAT", proid=bad, config={"proid_auth": cmd(0), "authz": cmd(0)})["code"], 2, repr(bad))

    def test_bad_environment_and_unknown_method(self):
        self.assertEqual(a.check("sso", "", config={})["code"], 2)
        self.assertEqual(a.check("kerberos", "UAT", config={})["code"], 2)

    def test_command_output_never_leaks_secrets(self):
        r = a.check("proid", "UAT", proid="svc_app", config={"proid_auth": cmd(1, "login failed password=hunter2 token: abc123"), "authz": cmd(0)})
        self.assertNotIn("hunter2", str(r))
        self.assertNotIn("abc123", str(r))

    def test_missing_command_is_reported_not_crashed(self):
        r = a.check("sso", "UAT", config={"sso_status": "/no/such/command", "authz": cmd(0)})
        self.assertIn(r["code"], (a.AUTHN_FAILED,))

    def test_placeholders_are_filled_after_splitting(self):
        r = a.check("proid", "UAT", "My Project", "1.0", proid="svc_app", config={
            "proid_auth": "%s -c \"import sys; sys.exit(0 if sys.argv[1]=='svc_app' and sys.argv[2]=='UAT' else 1)\" {proid} {environment}" % PY, "authz": cmd(0)})
        self.assertEqual(r["code"], 0, r)

    def test_cli_exit_codes(self):
        code, out = helpers.run_main(a.main, ["--method", "sso", "--environment", "UAT"])
        self.assertEqual(code, a.NOT_CONFIGURED)
        self.assertIn("Not configured", out)


if __name__ == "__main__":
    unittest.main()
