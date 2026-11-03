#!/usr/bin/env python3
"""Shows the upload plan, then runs the EXISTING upload script after the user has confirmed.

  run_upload.py plan    <inputs> --auth-method sso|proid|unverified [--proid NAME]
  run_upload.py execute <inputs> --auth-method ... --plan-hash HASH --confirmed yes [--ack-unverified-auth] [--timeout SECONDS]

<inputs> are --environment --properties --server --meta --project --release --distribution-system [--script PATH] [--cwd DIR] [--json]

The command that runs is exactly:
  /ms/dist/edap/PROJ/fdc_automation/prod/scripts/casc_upload_manifest.py -s <server> -m <meta> -p <project> -r <release> -e <environment> -md <distribution system>
It is started without a shell, in the folder that holds casc.properties. Nothing else is uploaded, modified or deleted by this skill.

Exit codes: 0 upload SUCCEEDED (verified), 1 upload FAILED, 3 script exited 0 but success could not be verified, 2 refused or usage error.
"""
import argparse
import json
import os
import shlex
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fdc_common as c  # noqa: E402
import validate_upload_prerequisites as v  # noqa: E402

DEFAULT_TIMEOUT = 3600
MAX_OUTPUT_CHARS = 20000

HINTS = {
    "Authentication": "Sign in again with the approved SSO flow (or the PROID flow) and re-run the checks before trying the upload again.",
    "Authorization": "Your identity is not allowed to upload to this environment. Ask the FDC administrators for access; do not try another environment.",
    "Invalid environment": "The script does not know this environment name. Ask the user for the exact name the script expects.",
    "Missing configuration": "The script could not find a required file or property. Check where it expects casc.properties (validate_upload_prerequisites.py --inspect-script).",
    "Invalid script arguments": "The script rejected one of -s -m -p -r -e -md. Check the values with the user; do not guess.",
    "Python execution": "The script could not run (interpreter, module or permissions). Check that python and the script's dependencies are available.",
    "FDC connectivity": "FDC could not be reached or the request timed out. Check the network or VPN and the server value, then try again.",
    "Manifest validation": "FDC or the script rejected the manifest. Read the validation message in the output; the manifest itself is not changed by this skill.",
    "Other script error": "Read the output above; the script reported a failure that does not match a known category.",
}


def build_plan(ns):
    """(report, plan or None). plan is only built when every mandatory check passed."""
    values = {"environment": ns.environment, "server": ns.server, "meta": ns.meta, "project": ns.project, "release": ns.release,
              "distribution_system": ns.distribution_system}
    script = ns.script or c.DEFAULT_SCRIPT
    report = v.run_checks(values, ns.properties, script, ns.cwd)
    problems = []
    if ns.auth_method == "proid":
        err = c.check_proid(ns.proid)
        if err:
            problems.append(err)
    if problems:
        report["checks"].append({"name": "authentication", "status": v.FAIL, "message": " ".join(problems)})
        report["ok"] = False
    if not report["ok"]:
        return report, None
    d = report["derived"]
    argv = c.build_command(script, values)
    props_sha = c.file_sha256(d["properties"])
    info = d["properties_info"] or {}
    plan = {
        "argv": argv, "cwd": d["cwd"], "script": script, "properties": d["properties"], "properties_sha256": props_sha,
        "project_root": d["project_root"], "auth_method": ns.auth_method, "proid": ns.proid if ns.auth_method == "proid" else None,
        "values": values, "behaviour": info,
    }
    plan["hash"] = c.plan_hash(argv, d["cwd"], d["properties"], props_sha, ns.auth_method)
    return report, plan


def summary_lines(plan):
    v_ = plan["values"]
    auth = {"sso": "SSO", "proid": "PROID (%s)" % plan["proid"], "unverified": "NOT VERIFIED by the skill (the user acknowledged that the script enforces access)"}[plan["auth_method"]]
    lines = [
        "UPLOAD SUMMARY",
        "  Target FDC environment : %s" % v_["environment"],
        "  Server (-s)            : %s" % v_["server"],
        "  Meta (-m)              : %s" % v_["meta"],
        "  Project (-p)           : %s" % v_["project"],
        "  Release (-r)           : %s" % v_["release"],
        "  Distribution system(-md): %s" % v_["distribution_system"],
        "  casc.properties        : %s" % plan["properties"],
        "  Project root           : %s" % plan["project_root"],
        "  Runs in folder         : %s" % plan["cwd"],
        "  Authentication         : %s" % auth,
    ]
    b = plan["behaviour"] or {}
    bp = b.get("behaviour_property")
    if bp and bp.get("present"):
        lines.append("  Update behaviour       : %s=%s (as written in casc.properties; the existing script decides what it does)" % (bp["key"], bp["value"]))
    else:
        lines.append("  Update behaviour       : decided by the existing script from casc.properties; the skill does not interpret it")
    for cl in (b.get("candidate_lines") or [])[:6]:
        lines.append("      casc.properties line %d: %s=%s" % (cl["line"], cl["key"], cl["value"]))
    lines.append("  Command                : " + " ".join(shlex.quote(a) for a in plan["argv"]))
    lines.append("")
    lines.append("  WARNING: depending on casc.properties, this upload may DELETE existing FDC manifest entries that are not in the incoming manifest.")
    lines.append("  Plan hash: %s  (the upload only runs with this exact plan and an explicit confirmation)" % plan["hash"])
    return lines


def execute(plan, timeout=DEFAULT_TIMEOUT, success_pattern=None):
    """Runs the existing script. Returns {"result", "categories", "reason", "exit_code", "output"}."""
    timed_out = False
    exit_code = None
    output = ""
    try:
        proc = subprocess.run(plan["argv"], cwd=plan["cwd"], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
        exit_code = proc.returncode
        output = proc.stdout.decode("utf-8", "replace") if proc.stdout else ""
    except subprocess.TimeoutExpired as e:
        timed_out = True
        output = (e.stdout or b"").decode("utf-8", "replace") if isinstance(e.stdout, (bytes, bytearray)) else (e.stdout or "")
    except (FileNotFoundError, PermissionError, OSError) as e:
        exit_code = 126
        output = "Could not start the upload script: %s" % e
    verdict = c.classify_output(output, exit_code if exit_code is not None else 1, success_pattern, timed_out)
    if exit_code == 126 and "Could not start" in output:
        verdict["categories"] = ["Python execution"]
    verdict["exit_code"] = exit_code
    verdict["output"] = c.redact(output)[-MAX_OUTPUT_CHARS:]
    return verdict


def print_result(plan, verdict, as_json=False):
    if as_json:
        print(json.dumps({"plan_hash": plan["hash"], "environment": plan["values"]["environment"], "project": plan["values"]["project"],
                          "release": plan["values"]["release"], "auth_method": plan["auth_method"], **verdict}, indent=2))
        return
    r = verdict["result"]
    head = {"SUCCEEDED": "UPLOAD SUCCEEDED", "FAILED": "UPLOAD FAILED", "UNVERIFIED": "UPLOAD NOT VERIFIED (do not treat it as successful yet)"}[r]
    print("RESULT: %s" % head)
    print("  Environment : %s" % plan["values"]["environment"])
    print("  Project     : %s   Release: %s" % (plan["values"]["project"], plan["values"]["release"]))
    print("  Auth method : %s" % plan["auth_method"].upper())
    print("  Exit code   : %s   (%s)" % (verdict["exit_code"], verdict["reason"]))
    if verdict["categories"]:
        print("  Likely cause (heuristic, from the output): %s" % ", ".join(verdict["categories"]))
        for cat in verdict["categories"][:2]:
            print("  Troubleshooting: %s" % HINTS.get(cat, HINTS["Other script error"]))
    if r == "UNVERIFIED":
        print("  The script exited normally but nothing proves the manifest arrived. Check FDC, or ask the owners of the script for its success message and set FDC_UPLOAD_SUCCESS_PATTERN.")
    print("  --- script output (secrets redacted) ---")
    print(verdict["output"].rstrip() or "(no output)")


def main(argv=None):
    p = argparse.ArgumentParser(description="Plan and run the FDC manifest upload with the existing script.")
    p.add_argument("action", choices=["plan", "execute"])
    p.add_argument("--environment")
    p.add_argument("--properties")
    p.add_argument("--server")
    p.add_argument("--meta")
    p.add_argument("--project")
    p.add_argument("--release")
    p.add_argument("--distribution-system", dest="distribution_system")
    p.add_argument("--script")
    p.add_argument("--cwd")
    p.add_argument("--auth-method", required=True, choices=["sso", "proid", "unverified"])
    p.add_argument("--proid")
    p.add_argument("--plan-hash")
    p.add_argument("--confirmed", help="must be 'yes', and only after the user answered yes to the summary")
    p.add_argument("--ack-unverified-auth", action="store_true", help="the user accepted that the skill could not verify authentication/authorization")
    p.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT)
    p.add_argument("--json", action="store_true")
    ns = p.parse_args(argv)

    report, plan = build_plan(ns)
    if plan is None:
        for x in report["checks"]:
            if x["status"] == v.FAIL:
                print("[FAIL] %s: %s" % (x["name"], x["message"]))
        print("REFUSED: a mandatory check failed, nothing was run. Fix the items above and ask again.")
        return 2
    if ns.action == "plan":
        if ns.json:
            print(json.dumps({"plan_hash": plan["hash"], "summary": summary_lines(plan), "argv": plan["argv"], "cwd": plan["cwd"],
                              "warnings": [x for x in report["checks"] if x["status"] == v.WARN]}, indent=2))
        else:
            for x in report["checks"]:
                if x["status"] in (v.WARN,):
                    print("[WARN] %s: %s" % (x["name"], x["message"]))
            print("\n".join(summary_lines(plan)))
            print("\nShow this summary to the user and ask: \"Do you want to proceed?\" Run 'execute' only after an explicit yes.")
        return 0

    # execute: every guard must pass
    if ns.confirmed != "yes":
        print("REFUSED: no explicit confirmation. Show the summary, ask the user, and pass --confirmed yes only after they say yes.")
        return 2
    if ns.plan_hash != plan["hash"]:
        print("REFUSED: the plan changed since the summary was shown (different values, or casc.properties changed). Run 'plan' again, show the new summary and ask again.")
        return 2
    if ns.auth_method == "unverified" and not ns.ack_unverified_auth:
        print("REFUSED: authentication and authorization were not verified. The user must explicitly accept this (--ack-unverified-auth) or the checks must be configured.")
        return 2
    verdict = execute(plan, ns.timeout, c.env_config().get("success_pattern"))
    print_result(plan, verdict, ns.json)
    return {"SUCCEEDED": 0, "FAILED": 1, "UNVERIFIED": 3}[verdict["result"]]


if __name__ == "__main__":
    sys.exit(main())
