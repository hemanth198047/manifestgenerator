import contextlib
import io
import json
import os
import stat
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "scripts"))

FAKE_SCRIPT = """#!/usr/bin/env python3
import json, os, sys, time
with open(os.environ["FAKE_RECORD"], "w") as f:
    f.write(json.dumps({"argv": sys.argv[1:], "cwd": os.getcwd()}))
time.sleep(float(os.environ.get("FAKE_SLEEP", "0")))
sys.stdout.write(os.environ.get("FAKE_OUTPUT", ""))
sys.exit(int(os.environ.get("FAKE_EXIT", "0")))
"""

VALUES = dict(environment="UAT", server="fdc.example.org", meta="meta1", project="MyProject", release="1.0", distribution_system="dist1")


class Workspace(unittest.TestCase):
    """A temporary project with a casc.properties and a fake upload script (the real one is never touched)."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = os.path.realpath(self.tmp.name)
        os.makedirs(os.path.join(self.root, "project", ".git"))
        self.project = os.path.join(self.root, "project")
        self.props = os.path.join(self.project, "casc.properties")
        with open(self.props, "w") as f:
            f.write("# sample\nproject.name=MyProject\nrelease.name=1.0\nmanifest.update.mode=update\nservice.password=hunter2\n")
        self.script = os.path.join(self.root, "casc_upload_manifest.py")
        with open(self.script, "w") as f:
            f.write(FAKE_SCRIPT)
        os.chmod(self.script, os.stat(self.script).st_mode | stat.S_IXUSR)
        self.record = os.path.join(self.root, "record.json")
        self._env = dict(os.environ)
        for k in list(os.environ):
            if k.startswith("FDC_") or k.startswith("FAKE_"):
                del os.environ[k]
        os.environ["FAKE_RECORD"] = self.record
        self.addCleanup(self._restore)

    def _restore(self):
        os.environ.clear()
        os.environ.update(self._env)

    def recorded(self):
        with open(self.record) as f:
            return json.load(f)

    def argv(self, action, extra=()):
        v = VALUES
        return [action, "--environment", v["environment"], "--properties", self.props, "--server", v["server"], "--meta", v["meta"],
                "--project", v["project"], "--release", v["release"], "--distribution-system", v["distribution_system"], "--script", self.script] + list(extra)


def run_main(main, argv):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = main(argv)
    return code, out.getvalue()
