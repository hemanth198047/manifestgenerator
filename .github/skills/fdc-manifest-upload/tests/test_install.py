import os
import tempfile
import unittest

import helpers  # noqa: F401
import install_skill as i


class Install(unittest.TestCase):
    def test_locations(self):
        self.assertEqual(i.target_dir("claude", "user", home="/h"), "/h/.claude/skills/fdc-manifest-upload")
        self.assertEqual(i.target_dir("copilot", "user", home="/h"), "/h/.copilot/skills/fdc-manifest-upload")
        self.assertEqual(i.target_dir("claude", "project", "/p"), "/p/.claude/skills/fdc-manifest-upload")
        self.assertEqual(i.target_dir("copilot", "project", "/p"), "/p/.github/skills/fdc-manifest-upload")

    def test_copy_symlink_and_no_silent_overwrite(self):
        with tempfile.TemporaryDirectory() as t:
            dest, how = i.install("claude", "project", t)
            self.assertEqual(how, "copied")
            self.assertTrue(os.path.isfile(os.path.join(dest, "SKILL.md")))
            self.assertTrue(os.path.isfile(os.path.join(dest, "scripts", "run_upload.py")))
            with self.assertRaises(FileExistsError):
                i.install("claude", "project", t)
            dest, how = i.install("claude", "project", t, symlink=True, force=True)
            self.assertEqual(how, "linked")
            self.assertTrue(os.path.islink(dest))


if __name__ == "__main__":
    unittest.main()
