"""Shared helpers for the fdc-manifest-upload skill (Python 3.8+, standard library only).

Nothing here talks to FDC. The upload itself is always done by the existing script; these helpers only validate
inputs, build the command, redact secrets, describe casc.properties without interpreting it, and classify results.
"""
import hashlib
import json
import os
import re
import shlex

# The command to run, used exactly as given. Only the values after -s -m -p -r -e -md change.
DEFAULT_SCRIPT = "/ms/dist/edap/PROJ/fdc_automation/prod/scripts/casc_upload_manifest.py"
PROPERTIES_FILE_NAME = "casc.properties"

ENV_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
PROID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
CONTROL_RE = re.compile(r"[\x00-\x1f\x7f]")
SECRET_KEY_RE = re.compile(r"(pass(word|wd)?|secret|token|credential|private|api[-_.]?key|auth)", re.I)
BEHAVIOUR_KEY_RE = re.compile(r"(delete|remove|purge|prune|retain|orphan|sync|update|overwrite|replace|mode)", re.I)
PROJECT_MARKERS = (".git", ".hg", ".svn", "pom.xml", "build.gradle", "settings.gradle", "pyproject.toml", "setup.py", "package.json")

# (flag, field name, label) in the exact order of the upload command
ARGUMENTS = (
    ("-s", "server", "Server"),
    ("-m", "meta", "Meta"),
    ("-p", "project", "Project"),
    ("-r", "release", "Release"),
    ("-e", "environment", "Environment"),
    ("-md", "distribution_system", "Distribution System"),
)


# ------------------------------------------------------------------ validation of values

def check_environment(value):
    """Returns an error message, or None when the environment is acceptable."""
    if value is None or not str(value).strip():
        return "The environment is empty. Say which FDC environment to upload to."
    if not ENV_NAME_RE.fullmatch(value):
        return ("The environment '%s' contains characters that are not allowed. Use letters, digits, '.', '_' or '-' "
                "(at most 64 characters, starting with a letter or digit)." % _shown(value))
    return None


def check_argument(label, value):
    """Rules for -s -m -p -r -md values. The values are passed as separate arguments (never through a shell)."""
    if value is None or not str(value).strip():
        return "%s is missing. Ask the user for it; do not guess it." % label
    if value != value.strip():
        return "%s has leading or trailing spaces." % label
    if CONTROL_RE.search(value):
        return "%s contains a control character or a line break." % label
    if value.startswith("-"):
        return "%s starts with '-', which the upload script would read as an option, not a value." % label
    if len(value) > 256:
        return "%s is longer than 256 characters." % label
    return None


def check_proid(value):
    if value is None or not PROID_RE.fullmatch(value):
        return "The PROID is not valid. It is a name (letters, digits, '.', '_' or '-'), never a password or token."
    return None


def _shown(value):
    return CONTROL_RE.sub("?", str(value))[:80]


# ------------------------------------------------------------------ command and plan

def build_command(script, values):
    """[script, -s, server, -m, meta, -p, project, -r, release, -e, environment, -md, distribution_system].

    This is the only command the skill runs for the upload. It is a list, so no shell is involved.
    """
    argv = [script]
    for flag, field, _label in ARGUMENTS:
        argv.extend([flag, values[field]])
    return argv


def file_sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def plan_hash(argv, cwd, properties_path, properties_sha256, auth_method):
    """Binds the confirmation to exactly what was shown to the user, including the content of casc.properties."""
    blob = json.dumps({"argv": argv, "cwd": cwd, "properties": properties_path, "properties_sha256": properties_sha256,
                       "auth": auth_method}, sort_keys=True)
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()[:16]


# ------------------------------------------------------------------ casc.properties (read only, never interpreted)

def parse_properties(text):
    """[(key, value, line number)] for key=value, key:value or 'key value' lines. Comments (# or !) are skipped."""
    out = []
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        raw = lines[i]
        start = i + 1
        i += 1
        stripped = raw.strip()
        if not stripped or stripped[0] in "#!":
            continue
        while stripped.endswith("\\") and i < len(lines):          # line continuation
            stripped = stripped[:-1] + lines[i].strip()
            i += 1
        m = re.match(r"^([^=:\s]+)\s*[=:\s]\s*(.*)$", stripped)
        if m:
            out.append((m.group(1), m.group(2).strip(), start))
        else:
            out.append((stripped, "", start))
    return out


def describe_properties(entries, delete_key=None, project_key=None, release_key=None):
    """Facts about casc.properties for the summary. Nothing is interpreted: values are shown as written
    (except values of secret-looking keys, which are hidden)."""
    by_key = {}
    for key, value, _line in entries:
        by_key.setdefault(key, value)

    def shown(key):
        return "<hidden>" if SECRET_KEY_RE.search(key) else by_key[key]

    info = {"entries": len(entries), "behaviour_property": None, "candidate_lines": []}
    if delete_key:
        info["behaviour_property"] = {"key": delete_key, "present": delete_key in by_key,
                                      "value": shown(delete_key) if delete_key in by_key else None}
    for key, value, line in entries:
        if BEHAVIOUR_KEY_RE.search(key) and key != delete_key:
            info["candidate_lines"].append({"line": line, "key": key, "value": "<hidden>" if SECRET_KEY_RE.search(key) else value})
    info["project_value"] = by_key.get(project_key) if project_key else None
    info["release_value"] = by_key.get(release_key) if release_key else None
    return info


def find_project_root(properties_path):
    """(project root, marker). Walks up from the folder of casc.properties to the nearest folder that has a project marker;
    without one, the folder of the file is the project root."""
    folder = os.path.dirname(os.path.realpath(properties_path))
    cur = folder
    while True:
        for marker in PROJECT_MARKERS:
            if os.path.exists(os.path.join(cur, marker)):
                return cur, marker
        parent = os.path.dirname(cur)
        if parent == cur:
            return folder, None
        cur = parent


# ------------------------------------------------------------------ secrets

_REDACTIONS = (
    (re.compile(r"(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}"), r"\1 <redacted>"),
    (re.compile(r"(?i)(pass(?:word|wd)?|secret|token|authorization|api[-_ ]?key|credential)(\s*[:=]\s*)(?:(?:bearer|basic)\s+)?(\"[^\"]*\"|'[^']*'|\S+)"), r"\1\2<redacted>"),
)


def redact(text):
    for pattern, repl in _REDACTIONS:
        text = pattern.sub(repl, text)
    return text


# ------------------------------------------------------------------ organisation commands (SSO, PROID, authorization)

def split_template(template, mapping):
    """An organisation-supplied command template to an argument list. The template is split first and the placeholders
    ({environment}, {project}, {release}, {proid}) are filled in afterwards, so a value can never add an argument."""
    argv = shlex.split(template)
    if not argv:
        raise ValueError("The command is empty.")
    out = []
    for token in argv:
        for key, value in mapping.items():
            token = token.replace("{" + key + "}", value)
        out.append(token)
    return out


# ------------------------------------------------------------------ results

FAILURE_CATEGORIES = (
    ("Authentication", r"401|unauthori[sz]ed|not logged in|login required|sso|session (expired|invalid)|token (expired|invalid)|kerberos|authentication (failed|error)"),
    ("Authorization", r"403|forbidden|not authori[sz]ed|permission denied|access denied|insufficient (privileges|permissions)|not entitled"),
    ("Invalid environment", r"(unknown|invalid|unsupported|no such) (fdc )?environment|environment .* not (found|supported)"),
    ("Missing configuration", r"casc\.properties.*(not found|missing|does not exist|no such)|(no such file|not found).*casc\.properties|missing (required )?propert"),
    ("Invalid script arguments", r"usage:|unrecognized arguments|the following arguments are required|invalid choice|expected one argument"),
    ("Python execution", r"traceback \(most recent call last\)|modulenotfounderror|importerror|syntaxerror|command not found|bad interpreter|exec format error"),
    ("FDC connectivity", r"connection (refused|reset|error)|timed? ?out|name or service not known|could not resolve|unreachable|ssl|certificate|\b50[234]\b|max retries"),
    ("Manifest validation", r"invalid manifest|manifest .*(invalid|failed|error)|validation (failed|error)|schema|malformed"),
)
GENERIC_FAILURE_RE = re.compile(r"(?i)(\b(error|failed|failure|exception|fatal)\b|traceback \(most recent|\w+error:|\w+exception:)")


def classify_output(output, exit_code, success_pattern=None, timed_out=False):
    """Decides the result of an upload run. Never reports success on an error.

    SUCCEEDED  exit code 0, no error text, and the organisation's success pattern matched
    UNVERIFIED exit code 0, no error text, but nothing proves the upload (no success pattern configured or it did not match)
    FAILED     any other case, with the likely categories (heuristics, from the text of the output)
    """
    text = output or ""
    cats = [name for name, pat in FAILURE_CATEGORIES if re.search(pat, text, re.I)]
    if timed_out:
        cats = ["FDC connectivity"] + [c for c in cats if c != "FDC connectivity"]
    errorish = bool(GENERIC_FAILURE_RE.search(text))
    if timed_out or exit_code != 0:
        return {"result": "FAILED", "categories": cats or ["Other script error"], "reason": "timed out" if timed_out else "exit code %s" % exit_code}
    if cats and errorish:
        return {"result": "FAILED", "categories": cats, "reason": "exit code 0 but the output reports an error"}
    if success_pattern:
        try:
            matched = re.search(success_pattern, text, re.M) is not None
        except re.error:
            matched = False
        if matched:
            return {"result": "SUCCEEDED", "categories": [], "reason": "exit code 0 and the success pattern matched"}
        return {"result": "UNVERIFIED", "categories": [], "reason": "exit code 0 but the success pattern did not match the output"}
    return {"result": "UNVERIFIED", "categories": [], "reason": "exit code 0, but no success pattern is configured, so the upload cannot be independently verified"}


def env_config():
    """Organisation integration points, all optional (see references/authentication.md)."""
    g = os.environ.get
    return {
        "delete_key": g("FDC_PROPS_DELETE_KEY"), "project_key": g("FDC_PROPS_PROJECT_KEY"), "release_key": g("FDC_PROPS_RELEASE_KEY"),
        "sso_status": g("FDC_SSO_STATUS_CMD"), "sso_login": g("FDC_SSO_LOGIN_CMD"), "authz": g("FDC_AUTHZ_CHECK_CMD"),
        "proid_auth": g("FDC_PROID_AUTH_CMD"), "success_pattern": g("FDC_UPLOAD_SUCCESS_PATTERN"),
    }
