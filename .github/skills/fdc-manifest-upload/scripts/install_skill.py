#!/usr/bin/env python3
"""Installs this skill folder where an AI CLI looks for skills. The upload logic is not copied anywhere: this skill only wraps
the existing casc_upload_manifest.py, which stays where it is.

  install_skill.py --agent claude  --scope user|project [--project-dir DIR] [--symlink] [--force]
  install_skill.py --agent copilot --scope user|project [--project-dir DIR] [--symlink] [--force]

Claude Code  : ~/.claude/skills/fdc-manifest-upload   or <project>/.claude/skills/fdc-manifest-upload
Copilot      : ~/.copilot/skills/fdc-manifest-upload  or <project>/.github/skills/fdc-manifest-upload
An existing installation is never overwritten unless --force is given.
"""
import argparse
import os
import shutil
import sys

SKILL_NAME = "fdc-manifest-upload"
SKILL_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def target_dir(agent, scope, project_dir=None, home=None):
    home = home or os.path.expanduser("~")
    if scope == "user":
        base = os.path.join(home, ".claude" if agent == "claude" else ".copilot", "skills")
    else:
        base = os.path.join(os.path.abspath(project_dir or os.getcwd()), ".claude" if agent == "claude" else ".github", "skills")
    return os.path.join(base, SKILL_NAME)


def install(agent, scope, project_dir=None, symlink=False, force=False, home=None):
    dest = target_dir(agent, scope, project_dir, home)
    if os.path.realpath(dest) == os.path.realpath(SKILL_DIR):
        return dest, "already in place (this is the skill folder itself)"
    if os.path.lexists(dest):
        if not force:
            raise FileExistsError("%s already exists. Use --force to replace it." % dest)
        if os.path.islink(dest) or os.path.isfile(dest):
            os.remove(dest)
        else:
            shutil.rmtree(dest)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    if symlink:
        os.symlink(SKILL_DIR, dest)
        return dest, "linked"
    shutil.copytree(SKILL_DIR, dest, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    return dest, "copied"


def main(argv=None):
    p = argparse.ArgumentParser(description="Install the fdc-manifest-upload skill for Claude Code or GitHub Copilot.")
    p.add_argument("--agent", required=True, choices=["claude", "copilot"])
    p.add_argument("--scope", required=True, choices=["user", "project"])
    p.add_argument("--project-dir")
    p.add_argument("--symlink", action="store_true")
    p.add_argument("--force", action="store_true")
    ns = p.parse_args(argv)
    try:
        dest, how = install(ns.agent, ns.scope, ns.project_dir, ns.symlink, ns.force)
    except FileExistsError as e:
        print(str(e))
        return 1
    print("%s: %s" % (how, dest))
    return 0


if __name__ == "__main__":
    sys.exit(main())
