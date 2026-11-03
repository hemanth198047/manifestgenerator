#!/usr/bin/env python3
"""Checks everything that can be checked before an FDC manifest upload (read only; nothing is uploaded or changed).

Usage:
  validate_upload_prerequisites.py --environment ENV --properties /path/to/casc.properties \
      --server S --meta M --project P --release R --distribution-system MD [--script PATH] [--cwd DIR] [--json]
  validate_upload_prerequisites.py --inspect-script [--script PATH]     show how the upload script mentions casc.properties

Exit codes: 0 all mandatory checks passed, 1 at least one check failed, 2 usage error.
"""
import argparse
import json
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fdc_common as c  # noqa: E402

PASS, FAIL, WARN, INFO = "PASS", "FAIL", "WARN", "INFO"


def _check(checks, name, status, message):
    checks.append({"name": name, "status": status, "message": message})


def run_checks(values, properties_path, script=None, cwd_override=None, config=None):
    """Returns {"ok", "checks", "derived"}. values: server, meta, project, release, environment, distribution_system."""
    config = config if config is not None else c.env_config()
    script = script or c.DEFAULT_SCRIPT
    checks = []
    derived = {"script": script, "properties": None, "project_root": None, "cwd": None, "python": None, "properties_info": None}

    # 1. environment
    err = c.check_environment(values.get("environment"))
    _check(checks, "environment", FAIL if err else PASS, err or "Target environment: %s" % values["environment"])

    # 2. script arguments (-s -m -p -r -md)
    for flag, field, label in c.ARGUMENTS:
        if field == "environment":
            continue
        err = c.check_argument(label, values.get(field))
        _check(checks, "argument %s" % flag, FAIL if err else PASS, err or "%s (%s) = %s" % (label, flag, values[field]))

    # 3. casc.properties
    props_ok = False
    if not properties_path:
        _check(checks, "casc.properties", FAIL, "The location of casc.properties was not given. Ask the user for the absolute path.")
    else:
        path = os.path.abspath(os.path.expanduser(properties_path))
        if os.path.isdir(path):
            candidate = os.path.join(path, c.PROPERTIES_FILE_NAME)
            if os.path.isfile(candidate):
                path = candidate
        if not os.path.exists(path):
            _check(checks, "casc.properties", FAIL, "No file at %s. Check the path with the user." % path)
        elif not os.path.isfile(path):
            _check(checks, "casc.properties", FAIL, "%s is not a file." % path)
        elif not os.access(path, os.R_OK):
            _check(checks, "casc.properties", FAIL, "%s exists but is not readable by you." % path)
        else:
            props_ok = True
            derived["properties"] = os.path.realpath(path)
            _check(checks, "casc.properties", PASS, "Found and readable: %s" % derived["properties"])
            if os.path.basename(path) != c.PROPERTIES_FILE_NAME:
                _check(checks, "casc.properties name", WARN, "The file is called '%s', not '%s'. The upload script looks for '%s'; confirm with the user."
                       % (os.path.basename(path), c.PROPERTIES_FILE_NAME, c.PROPERTIES_FILE_NAME))

    # 4. project root and working folder
    if props_ok:
        root, marker = c.find_project_root(derived["properties"])
        derived["project_root"] = root
        _check(checks, "project root", PASS, "Project root: %s%s" % (root, " (found by %s)" % marker if marker else " (the folder of casc.properties; no project marker found above it)"))
        folder = os.path.dirname(derived["properties"])
        cwd = os.path.abspath(os.path.expanduser(cwd_override)) if cwd_override else folder
        derived["cwd"] = cwd
        if not os.path.isdir(cwd):
            _check(checks, "working folder", FAIL, "The working folder %s does not exist." % cwd)
        elif not os.path.isfile(os.path.join(cwd, c.PROPERTIES_FILE_NAME)) or os.path.realpath(os.path.join(cwd, c.PROPERTIES_FILE_NAME)) != derived["properties"]:
            _check(checks, "working folder", WARN, "casc.properties is not directly inside the working folder %s, so a script that looks in the current folder would not find it. "
                   "Use --cwd with the folder that holds the file, or ask the user where the script expects it (see --inspect-script)." % cwd)
        else:
            _check(checks, "working folder", PASS, "The script will run in %s, which holds casc.properties." % cwd)
        if os.path.realpath(folder) != os.path.realpath(root):
            _check(checks, "properties location", WARN, "casc.properties is not in the project root (%s). If the script looks for it in the project root, it will not find this file. "
                   "Do not move or copy the file without the user's approval." % root)

    # 5. the upload script
    if not os.path.isfile(script):
        _check(checks, "upload script", FAIL, "The upload script was not found at %s. This is the existing script; the skill never copies or recreates it." % script)
    else:
        if not os.access(script, os.X_OK):
            _check(checks, "upload script", FAIL, "%s exists but is not executable by you. The command is run as given (without 'python'); ask the user how to proceed." % script)
        else:
            _check(checks, "upload script", PASS, "Found and executable: %s" % script)
        try:
            with open(script, "rb") as f:
                first = f.readline(512).decode("utf-8", "replace").strip()
        except OSError:
            first = ""
        if first.startswith("#!"):
            interp = first[2:].strip().split()
            target = interp[0] if interp else ""
            if target == "/usr/bin/env" and len(interp) > 1:
                found = shutil.which(interp[1])
                _check(checks, "python", PASS if found else FAIL, ("Interpreter '%s' found at %s." % (interp[1], found)) if found else
                       "The script starts with '#!/usr/bin/env %s' but '%s' is not on PATH." % (interp[1], interp[1]))
                derived["python"] = found
            elif target:
                ok = os.path.exists(target)
                _check(checks, "python", PASS if ok else FAIL, ("Interpreter %s exists." % target) if ok else "The script's interpreter %s does not exist on this machine." % target)
                derived["python"] = target if ok else None
        else:
            found = shutil.which("python3") or shutil.which("python")
            _check(checks, "python", PASS if found else FAIL, ("Python found at %s." % found) if found else "Python is not installed or not on PATH.")
            derived["python"] = found

    # 6. consistency with casc.properties, only for keys the organisation has named
    if props_ok:
        try:
            with open(derived["properties"], "r", encoding="utf-8", errors="replace") as f:
                entries = c.parse_properties(f.read())
        except OSError as e:
            entries = []
            _check(checks, "casc.properties read", FAIL, "Could not read the file: %s" % e)
        info = c.describe_properties(entries, config.get("delete_key"), config.get("project_key"), config.get("release_key"))
        derived["properties_info"] = info
        for label, field, key_cfg, env in (("project", "project", config.get("project_key"), "FDC_PROPS_PROJECT_KEY"),
                                           ("release", "release", config.get("release_key"), "FDC_PROPS_RELEASE_KEY")):
            if not key_cfg:
                _check(checks, "%s consistency" % label, INFO, "Not checked: the name of the %s property in casc.properties is not configured (%s). The skill does not guess it." % (label, env))
            elif info["%s_value" % label] is None:
                _check(checks, "%s consistency" % label, WARN, "casc.properties has no property '%s', so the %s could not be compared." % (key_cfg, label))
            elif info["%s_value" % label] != values.get(field):
                _check(checks, "%s consistency" % label, FAIL, "You gave %s '%s' but casc.properties says %s=%s. Ask the user which is right; do not change either." %
                       (label, values.get(field), key_cfg, info["%s_value" % label]))
            else:
                _check(checks, "%s consistency" % label, PASS, "%s matches casc.properties (%s=%s)." % (label.capitalize(), key_cfg, info["%s_value" % label]))
        bp = info["behaviour_property"]
        if bp:
            _check(checks, "update behaviour property", INFO, ("%s=%s (shown as written; the upload script, not the skill, decides what it means)" % (bp["key"], bp["value"])) if bp["present"]
                   else "casc.properties has no property '%s'; the script decides the behaviour." % bp["key"])
        else:
            _check(checks, "update behaviour property", INFO, "The name of the property that controls updating versus deleting is not configured (FDC_PROPS_DELETE_KEY), so the skill cannot say which behaviour applies.")
        if info["candidate_lines"]:
            lines = "; ".join("line %d: %s=%s" % (x["line"], x["key"], x["value"]) for x in info["candidate_lines"][:10])
            _check(checks, "properties that may matter", INFO, "Shown as written, not interpreted: " + lines)

    ok = all(x["status"] != FAIL for x in checks)
    return {"ok": ok, "checks": checks, "derived": derived}


def inspect_script(script, limit=40):
    """Read-only: lines of the upload script that mention casc.properties or how it finds files. Helps to confirm where the script looks."""
    needles = ("casc.properties", "getcwd", "os.environ", "expanduser", "properties", "argparse", "'-e'", '"-e"', "environment")
    out = []
    with open(script, "r", encoding="utf-8", errors="replace") as f:
        for n, line in enumerate(f, 1):
            if any(k in line for k in needles):
                out.append("%d: %s" % (n, c.redact(line.rstrip())[:200]))
                if len(out) >= limit:
                    break
    return out


def main(argv=None):
    p = argparse.ArgumentParser(description="Check the prerequisites of an FDC manifest upload (read only).")
    p.add_argument("--environment")
    p.add_argument("--properties", help="absolute path of casc.properties (or the folder that holds it)")
    p.add_argument("--server")
    p.add_argument("--meta")
    p.add_argument("--project")
    p.add_argument("--release")
    p.add_argument("--distribution-system", dest="distribution_system")
    p.add_argument("--script", help="override the upload script path (tests, or a non-standard installation the user named)")
    p.add_argument("--cwd", help="folder to run the script in (default: the folder that holds casc.properties)")
    p.add_argument("--json", action="store_true")
    p.add_argument("--inspect-script", action="store_true", help="print the lines of the upload script that mention casc.properties and how it finds files")
    ns = p.parse_args(argv)
    script = ns.script or c.DEFAULT_SCRIPT
    if ns.inspect_script:
        if not os.path.isfile(script):
            print("The upload script was not found at %s." % script)
            return 1
        lines = inspect_script(script)
        print("Read-only view of %s (lines that mention casc.properties or how the script finds files):" % script)
        print("\n".join(lines) if lines else "(no matching lines)")
        return 0
    values = {"environment": ns.environment, "server": ns.server, "meta": ns.meta, "project": ns.project, "release": ns.release,
              "distribution_system": ns.distribution_system}
    report = run_checks(values, ns.properties, script, ns.cwd)
    if ns.json:
        print(json.dumps(report, indent=2))
    else:
        for x in report["checks"]:
            print("[%s] %s: %s" % (x["status"], x["name"], x["message"]))
        print("RESULT: %s" % ("all mandatory checks passed" if report["ok"] else "validation failed; fix the FAIL items before going on"))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
