import unittest

from tests import _helpers  # noqa: F401
import security as S


class KeyCheck(unittest.TestCase):
    def test_unconfigured_when_missing_or_placeholder(self):
        self.assertEqual(S.check_key("x", {}), S.UNCONFIGURED)
        self.assertEqual(S.check_key("x", {"INTERNAL_KEY": "change_me"}), S.UNCONFIGURED)
        self.assertEqual(S.check_key("x", {"INTERNAL_KEY": "  "}), S.UNCONFIGURED)

    def test_ok_and_unauthorized(self):
        env = {"INTERNAL_KEY": "secret-123"}
        self.assertEqual(S.check_key("secret-123", env), S.OK)
        self.assertEqual(S.check_key("nope", env), S.UNAUTHORIZED)
        self.assertEqual(S.check_key(None, env), S.UNAUTHORIZED)
        self.assertEqual(S.check_key("", env), S.UNAUTHORIZED)

    def test_rotation_accepts_current_and_previous(self):
        env = {"INTERNAL_KEY": "new-key", "INTERNAL_KEY_PREVIOUS": "old-key"}
        self.assertEqual(S.check_key("new-key", env), S.OK)
        self.assertEqual(S.check_key("old-key", env), S.OK)
        self.assertEqual(S.check_key("older", env), S.UNAUTHORIZED)

    def test_previous_removed_after_rotation(self):
        self.assertEqual(S.check_key("old-key", {"INTERNAL_KEY": "new-key"}), S.UNAUTHORIZED)

    def test_non_ascii_key_does_not_crash(self):
        self.assertEqual(S.check_key("khóa-việt", {"INTERNAL_KEY": "khóa-việt"}), S.OK)


if __name__ == "__main__":
    unittest.main()
