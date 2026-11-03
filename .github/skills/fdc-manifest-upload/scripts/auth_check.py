#!/usr/bin/env python3
"""Verifies authentication and authorization for an FDC upload through the organisation's own commands.

This skill does not know how your organisation signs in, so it never invents a mechanism. You configure the approved commands
in environment variables (see references/authentication.md); each is run without a shell and only its exit code (0 = yes) is used.

  FDC_SSO_STATUS_CMD   exit 0 when a valid SSO session exists                (placeholders: {environment})
  FDC_SSO_LOGIN_CMD    the SSO sign-in command; shown to the user, never run  (placeholders: {environment})
  FDC_PROID_AUTH_CMD   verifies/signs in the PROID through the approved way  (placeholders: {environment} {proid})
  FDC_AUTHZ_CHECK_CMD  exit 0 when the signed-in identity may upload to the environment
                                                                             (placeholders: {environment} {project} {release} {proid})

Usage:
  auth_check.py --method sso   --environment ENV [--project P] [--release R]
  auth_check.py --method proid --environment ENV --proid NAME [--project P] [--release R]

Exit codes: 0 authentication and authorization verified, 10 SSO session not active (sign in, then run again),
            11 a needed check is not configured (nothing was verified), 12 authentication failed, 13 authorization failed, 2 usage error.
Passwords and tokens are never asked for here; anything secret is entered by the user in the organisation's own sign-in tool.
"""
import argparse
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fdc_common as c  # noqa: E402

VERIFIED, NOT_ACTIVE, NOT_CONFIGURED, AUTHN_FAILED, AUTHZ_FAILED = 0, 10, 11, 12, 13


def run_cmd(template, mapping, timeout=120):
    """(exit code or None, short redacted detail). The command is split first and runs without a shell."""
    try:
        argv = c.split_template(template, mapping)
    except ValueError as e:
        return None, "The configured command is not usable: %s" % e
    try:
        proc = subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    except FileNotFoundError:
        return None, "The configured command was not found: %s" % argv[0]
    except PermissionError:
        return None, "The configured command is not executable: %s" % argv[0]
    except subprocess.TimeoutExpired:
        return None, "The configured command did not finish within %d seconds." % timeout
    text = proc.stdout.decode("utf-8", "replace") if proc.stdout else ""
    lines = [c.redact(x)[:200] for x in text.strip().splitlines()[-5:]]
    return proc.returncode, " | ".join(lines)


def check(method, environment, project=None, release=None, proid=None, config=None, timeout=120):
    """Returns {"code", "method", "authentication", "authorization", "messages", "login_command"}."""
    config = config if config is not None else c.env_config()
    res = {"method": method, "authentication": "not_checked", "authorization": "not_checked", "messages": [], "login_command": None}
    err = c.check_environment(environment)
    if err:
        res.update(code=2, messages=[err])
        return res
    mapping = {"environment": environment, "project": project or "", "release": release or "", "proid": proid or ""}
    missing = []

    if method == "sso":
        if not config.get("sso_status"):
            missing.append("FDC_SSO_STATUS_CMD (how to tell whether an SSO session is active)")
        else:
            code, detail = run_cmd(config["sso_status"], mapping, timeout)
            if code == 0:
                res["authentication"] = "verified"
                res["messages"].append("A valid SSO session is active.")
            else:
                res["authentication"] = "not_active" if code is not None else "failed"
                res["messages"].append("No active SSO session." if code is not None else detail)
                if config.get("sso_login"):
                    try:
                        res["login_command"] = " ".join(c.split_template(config["sso_login"], mapping))
                    except ValueError:
                        pass
                    res["messages"].append("Ask the user to complete the SSO sign-in in their own terminal, then run this check again.")
                else:
                    missing.append("FDC_SSO_LOGIN_CMD (the approved SSO sign-in command to show the user)")
                res["code"] = NOT_ACTIVE if code is not None else AUTHN_FAILED
                if missing:
                    res["messages"].append("Not configured: " + "; ".join(missing))
                return res
    elif method == "proid":
        perr = c.check_proid(proid)
        if perr:
            res.update(code=2, messages=[perr])
            return res
        if not config.get("proid_auth"):
            missing.append("FDC_PROID_AUTH_CMD (the approved way to authenticate a PROID)")
        else:
            code, detail = run_cmd(config["proid_auth"], mapping, timeout)
            if code == 0:
                res["authentication"] = "verified"
                res["messages"].append("PROID '%s' authenticated through the approved command." % proid)
            else:
                res["authentication"] = "failed"
                res["messages"].append("PROID authentication failed. %s" % (detail or ""))
                res["code"] = AUTHN_FAILED
                return res
    else:
        res.update(code=2, messages=["Unknown method '%s'. Use sso or proid." % method])
        return res

    # authorization is checked separately: being signed in does not mean being allowed to upload to this environment
    if not config.get("authz"):
        missing.append("FDC_AUTHZ_CHECK_CMD (how to check that the identity may upload to the environment)")
    elif res["authentication"] == "verified":
        code, detail = run_cmd(config["authz"], mapping, timeout)
        if code == 0:
            res["authorization"] = "verified"
            res["messages"].append("Authorized to upload to %s." % environment)
        else:
            res["authorization"] = "failed"
            res["messages"].append("Not authorized to upload to %s. %s" % (environment, detail or ""))
            res["code"] = AUTHZ_FAILED
            return res
    if missing:
        res["messages"].append("Not configured, so this was NOT verified: " + "; ".join(missing))
        res["code"] = NOT_CONFIGURED
        return res
    res["code"] = VERIFIED
    return res


def main(argv=None):
    p = argparse.ArgumentParser(description="Verify authentication and authorization for an FDC upload.")
    p.add_argument("--method", required=True, choices=["sso", "proid"])
    p.add_argument("--environment", required=True)
    p.add_argument("--project")
    p.add_argument("--release")
    p.add_argument("--proid", help="the PROID name (never a password or token)")
    p.add_argument("--timeout", type=int, default=120)
    p.add_argument("--json", action="store_true")
    ns = p.parse_args(argv)
    res = check(ns.method, ns.environment, ns.project, ns.release, ns.proid, timeout=ns.timeout)
    if ns.json:
        print(json.dumps(res, indent=2))
    else:
        print("Method: %s" % res["method"].upper())
        print("Authentication: %s" % res["authentication"])
        print("Authorization: %s" % res["authorization"])
        for m in res["messages"]:
            print("- " + m)
        if res.get("login_command"):
            print("SSO sign-in command (run it yourself, the skill does not run it): %s" % res["login_command"])
    return res["code"]


if __name__ == "__main__":
    sys.exit(main())
