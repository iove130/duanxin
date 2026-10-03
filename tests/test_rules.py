# -*- coding: utf-8 -*-
"""规则引擎单元测试（纯 Python，可在桌面直接运行）。"""
import os
import shutil
import sys
import tempfile
import time
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from common import config_store, rules  # noqa: E402


class TestNumberMatch(unittest.TestCase):
    def test_exact(self):
        self.assertTrue(rules.match_number("13800138000", "13800138000"))

    def test_country_code(self):
        self.assertTrue(rules.match_number("+8613800138000", "13800138000"))
        self.assertTrue(rules.match_number("008613800138000", "13800138000"))
        self.assertTrue(rules.match_number("8613800138000", "13800138000"))

    def test_prefix_wildcard(self):
        self.assertTrue(rules.match_number("1069020099123", "1069*"))
        self.assertFalse(rules.match_number("13800138000", "1069*"))

    def test_suffix_wildcard(self):
        self.assertTrue(rules.match_number("9558812345", "*12345"))
        self.assertTrue(rules.match_number("+869558812345", "*12345"))

    def test_contains_wildcard(self):
        self.assertTrue(rules.match_number("95588", "*95588*"))
        self.assertTrue(rules.match_number("xx95588yy", "*95588*"))

    def test_sender_allow_list(self):
        cfg = {"senders": ["10086", "1069*"], "sender_mode": "allow"}
        ok, _ = rules.sender_allowed("10086", cfg["senders"], "allow")
        self.assertTrue(ok)
        ok, _ = rules.sender_allowed("13800138000", cfg["senders"], "allow")
        self.assertFalse(ok)

    def test_sender_allow_empty_means_all(self):
        ok, reason = rules.sender_allowed("13800138000", [], "allow")
        self.assertTrue(ok)
        self.assertIn("未设置", reason)

    def test_sender_block_list(self):
        ok, _ = rules.sender_allowed("10086", ["10086"], "block")
        self.assertFalse(ok)
        ok, _ = rules.sender_allowed("13800138000", ["10086"], "block")
        self.assertTrue(ok)


class TestKeywordMatch(unittest.TestCase):
    def test_empty_keywords_passthrough(self):
        hit, _ = rules.keyword_hit("随便一句话", [], "any")
        self.assertTrue(hit)

    def test_any(self):
        cfg_kw = ["验证码", "余额"]
        self.assertTrue(rules.keyword_hit("您的验证码是 1234", cfg_kw, "any")[0])
        self.assertFalse(rules.keyword_hit("你好呀", cfg_kw, "any")[0])

    def test_all(self):
        cfg_kw = ["验证码", "登录"]
        self.assertTrue(rules.keyword_hit("验证码用于登录", cfg_kw, "all")[0])
        self.assertFalse(rules.keyword_hit("验证码到手", cfg_kw, "all")[0])

    def test_case_insensitive_by_default(self):
        self.assertTrue(rules.keyword_hit("Auth Code", ["auth"], "any", False)[0])
        self.assertFalse(rules.keyword_hit("Auth Code", ["auth"], "any", True)[0])

    def test_regex(self):
        self.assertTrue(rules.keyword_hit("订单号 A2023100301", [r"A\d{10}"], "regex")[0])
        self.assertFalse(rules.keyword_hit("订单号 X1", [r"A\d{10}"], "regex")[0])

    def test_exclude(self):
        blocked, word = rules.exclude_hit("验证码 1234 回T退订", ["退订"])
        self.assertTrue(blocked)
        self.assertEqual(word, "退订")

    def test_should_forward_integration(self):
        cfg = {
            "enabled": True,
            "senders": ["1069*"],
            "sender_mode": "allow",
            "keywords": ["验证码"],
            "keyword_mode": "any",
            "exclude_keywords": ["退订"],
            "case_sensitive": False,
        }
        ok, reason, word = rules.should_forward(
            {"from": "10690300123", "body": "您的验证码是 8821"}, cfg
        )
        self.assertTrue(ok, reason)
        self.assertEqual(word, "验证码")

        ok, reason, _ = rules.should_forward(
            {"from": "13800138000", "body": "您的验证码是 8821"}, cfg
        )
        self.assertFalse(ok)
        self.assertIn("白名单", reason)

        ok, reason, _ = rules.should_forward(
            {"from": "10690300123", "body": "验证码 8821 退订回T"}, cfg
        )
        self.assertFalse(ok)
        self.assertIn("屏蔽词", reason)

    def test_disabled_switch(self):
        cfg = {"enabled": False}
        ok, reason, _ = rules.should_forward({"from": "1", "body": "2"}, cfg)
        self.assertFalse(ok)
        self.assertIn("关闭", reason)


class TestRendering(unittest.TestCase):
    def test_render(self):
        tpl = "[转发] {from} {time} {body}"
        out = rules.render(tpl, {"from": "10086", "time": "2026-01-01 10:00", "body": "余额 10 元"})
        self.assertEqual(out, "[转发] 10086 2026-01-01 10:00 余额 10 元")

    def test_render_unknown_var_fallback(self):
        out = rules.render("{bad} 出问题", {"from": "x"})
        self.assertTrue(out)

    def test_split_disabled(self):
        parts = rules.split_text("a" * 1000, 500, False)
        self.assertEqual(len(parts), 1)

    def test_split_enabled(self):
        parts = rules.split_text("a" * 1200, 500, True)
        self.assertEqual(len(parts), 3)
        self.assertTrue(parts[0].startswith("(1/3)"))
        self.assertEqual(len(parts[0]), len("(1/3)") + 500)

    def test_split_short(self):
        self.assertEqual(rules.split_text("短", 500, True), ["短"])


class TestDedup(unittest.TestCase):
    def test_window(self):
        d = rules.Deduplicator(5)
        now = time.time()
        self.assertFalse(d.is_duplicate("10086", "验证码 1", now))
        self.assertTrue(d.is_duplicate("10086", "验证码 1", now + 1))
        self.assertFalse(d.is_duplicate("10086", "验证码 1", now + 301))

    def test_zero_window(self):
        d = rules.Deduplicator(0)
        now = time.time()
        self.assertFalse(d.is_duplicate("10086", "x", now))
        self.assertFalse(d.is_duplicate("10086", "x", now))


class TestParseList(unittest.TestCase):
    def test_mixed_separators(self):
        out = rules.parse_list("10086，1069*;\n95588、abc")
        self.assertEqual(out, ["10086", "1069*", "95588", "abc"])

    def test_empty(self):
        self.assertEqual(rules.parse_list("  \n  "), [])


class TestConfigStore(unittest.TestCase):
    def test_default_filled(self):
        cfg = config_store.load_config()
        for key in ("receivers", "keywords", "template", "max_len"):
            self.assertIn(key, cfg)

    def test_save_and_reload(self):
        original = config_store.load_config()
        try:
            cfg = dict(original)
            cfg["receivers"] = ["13800000000"]
            cfg["template"] = "test {body}"
            config_store.save_config(cfg)
            again = config_store.load_config()
            self.assertEqual(again["receivers"], ["13800000000"])
            self.assertEqual(again["template"], "test {body}")
        finally:
            config_store.save_config(original)

    def test_stop_flag(self):
        config_store.clear_stop_flag()
        self.assertFalse(config_store.should_stop())
        config_store.request_stop()
        self.assertTrue(config_store.should_stop())
        config_store.clear_stop_flag()
        self.assertFalse(config_store.should_stop())


if __name__ == "__main__":
    unittest.main(verbosity=2)
