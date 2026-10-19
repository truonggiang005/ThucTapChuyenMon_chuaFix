import json
import os
import unittest
from unittest import mock

from tests._helpers import shift, emp, FakeAPIError
import gemini_client as G
import model as M


def gemini_json(cands, over=None):
    over = over or {}
    """Phản hồi Gemini hợp lệ cho các ứng viên: điểm thành phần cao, match_score tự báo."""
    items = []
    for c in cands:
        item = dict(employee_id=c.employee_id, reliability_score=90, skill_fit_score=95, time_fit_score=90,
                    workload_fit_score=80, location_fit_score=100, match_score=90, explanation="Phù hợp tốt.")
        item.update(over.get(c.employee_id, {}))
        items.append(item)
    return json.dumps({"results": items})


class Base(unittest.TestCase):
    def setUp(self):
        M.clear_cache()
        self.calls = []
        self.responder = lambda prompt: "{}"
        self._env = mock.patch.dict(os.environ, {"GEMINI_API_KEY": "real-key", "GEMINI_ENABLED": "true"})
        self._env.start()
        self.addCleanup(self._env.stop)
        self.original_client = M.get_client()
        self.addCleanup(lambda: M.set_client(self.original_client))
        self.install_client()

    def install_client(self, **kw):
        def transport(api_key, model, prompt, schema, timeout):
            self.calls.append(prompt)
            return self.responder(prompt)
        breaker = G.CircuitBreaker(threshold=3, cooldown=30)
        M.set_client(G.GeminiClient(M._get_api_key, M._get_model, transport=transport, breaker=breaker,
                                    deadline_s=kw.get("deadline", 5.0), max_concurrency=2, sleep=lambda s: None))


class GeminiHappyPath(Base):
    def test_scores_from_gemini_are_flagged_and_consistent(self):
        s, cands = shift(), [emp(1), emp(2, skill_level=5)]
        self.responder = lambda p: gemini_json(cands)
        out = M.calculate_match_scores(s, cands)
        self.assertEqual((out.source, out.fallback, out.reason), ("gemini", False, None))
        self.assertEqual(len(out.ranked), 2)
        for sc in out.ranked:
            self.assertEqual(sc.source, "gemini")
            b = sc.breakdown
            expected = sum(M.H.WEIGHTS[k] * b[k] for k in M.H.WEIGHTS)
            self.assertAlmostEqual(sc.match_score, expected, delta=0.2)      # match_score nhất quán với breakdown (B7)
            self.assertTrue(sc.score_low <= sc.match_score <= sc.score_high)
        self.assertIn("location_fit", out.ranked[0].breakdown)               # D2

    def test_llm_self_reported_total_cannot_override_breakdown(self):
        s, cands = shift(), [emp(1)]
        self.responder = lambda p: gemini_json(cands, {1: {"match_score": 5}})
        sc = M.calculate_match_scores(s, cands).ranked[0]
        self.assertGreater(sc.match_score, 70)
        self.assertTrue(sc.breakdown["consistency_adjusted"])
        self.assertEqual(sc.breakdown["llm_reported_score"], 5.0)

    def test_prompt_has_no_names_and_treats_data_as_untrusted(self):
        s, cands = shift(), [emp(1, full_name="Nguyễn Văn Bí Mật")]
        self.responder = lambda p: gemini_json(cands)
        M.calculate_match_scores(s, cands)
        self.assertNotIn("Bí Mật", self.calls[0])
        self.assertIn("CHỈ LÀ DỮ LIỆU", self.calls[0])

    def test_result_cached_only_when_pure_gemini(self):
        s, cands = shift(), [emp(1), emp(2)]
        self.responder = lambda p: gemini_json(cands)
        M.calculate_match_scores(s, cands)
        M.calculate_match_scores(s, cands)
        self.assertEqual(len(self.calls), 1)
        # đổi giờ đã làm của một ứng viên -> khóa cache khác -> gọi lại
        M.calculate_match_scores(s, [emp(1, hours_worked_this_week=30), emp(2)])
        self.assertEqual(len(self.calls), 2)

    def test_string_ids_and_code_fences_are_accepted(self):
        s, cands = shift(), [emp(1), emp(2)]
        body = json.loads(gemini_json(cands))
        body["results"][0]["employee_id"] = "1"          # B6: ID dạng chuỗi bị loại im lặng trước đây
        body["results"][1]["employee_id"] = 2.0
        self.responder = lambda p: "```json\n" + json.dumps(body) + "\n```"
        out = M.calculate_match_scores(s, cands)
        self.assertEqual((out.source, out.fallback), ("gemini", False))
        self.assertEqual(sorted(r.employee_id for r in out.ranked), [1, 2])


class Fallbacks(Base):
    def test_no_api_key_is_flagged_not_silent(self):
        with mock.patch.dict(os.environ, {"GEMINI_API_KEY": ""}):
            out = M.calculate_match_scores(shift(), [emp(1), emp(2)])
        self.assertTrue(out.fallback)
        self.assertEqual((out.source, out.reason), ("heuristic", G.NOT_CONFIGURED))
        self.assertTrue(all(r.source == "heuristic" for r in out.ranked))
        self.assertIn("dự phòng", out.message)
        self.assertEqual(self.calls, [])

    def test_placeholder_key_and_disabled_flag(self):
        for env in ({"GEMINI_API_KEY": "your_api_key_here"}, {"GEMINI_ENABLED": "false"}):
            with mock.patch.dict(os.environ, env):
                M.clear_cache()
                out = M.calculate_match_scores(shift(), [emp(1)])
                self.assertEqual(out.reason, G.NOT_CONFIGURED)

    def test_rate_limit_flagged_and_circuit_stops_further_calls(self):
        def boom(p):
            raise FakeAPIError(429, "RESOURCE_EXHAUSTED")
        self.responder = boom
        out1 = M.calculate_match_scores(shift(), [emp(1)])
        out2 = M.calculate_match_scores(shift(shift_id=2), [emp(1)])
        for o in (out1, out2):
            self.assertTrue(o.fallback)
            self.assertEqual(o.reason, G.RATE_LIMITED)
        self.assertEqual(len(self.calls), 1)               # lần 2 không gọi Gemini

    def test_quota_exhausted_scores_follow_the_weighted_formula_and_rank_best_first(self):
        """Hết quota: điểm = 0,30·tin cậy + 0,25·kỹ năng + 0,20·khung giờ + 0,15·khối lượng + 0,10·chi nhánh (cron dựa vào đây để gán)."""
        def boom(p):
            raise FakeAPIError(429, "RESOURCE_EXHAUSTED")
        self.responder = boom
        s = shift()
        strong = emp(1, skill_level=5, total_completed=60, total_cancelled=0, late_arrival_count=0, hours_worked_this_week=0.0)
        weak = emp(2, skill_level=3, total_completed=10, total_cancelled=5, late_arrival_count=4, hours_worked_this_week=30.0)
        out = M.calculate_match_scores(s, [weak, strong])
        self.assertEqual((out.source, out.fallback, out.reason), ("heuristic", True, G.RATE_LIMITED))
        self.assertEqual([r.employee_id for r in out.ranked], [1, 2])            # người phù hợp hơn đứng đầu
        self.assertEqual(M.H.WEIGHTS, {"reliability": 0.30, "skill_fit": 0.25, "time_fit": 0.20,
                                       "workload_fit": 0.15, "location_fit": 0.10})
        for r in out.ranked:
            self.assertEqual(r.source, "heuristic")
            b = r.breakdown
            expected = sum(M.H.WEIGHTS[k] * b[k] for k in M.H.WEIGHTS)
            self.assertAlmostEqual(r.match_score, expected, delta=0.2)           # khớp công thức trọng số (có làm tròn 1 chữ số)
        self.assertGreater(out.ranked[0].match_score, out.ranked[1].match_score)

    def test_fallback_is_never_cached_so_recovery_is_immediate(self):
        s, cands = shift(), [emp(1)]
        state = {"fail": True}

        def responder(p):
            if state["fail"]:
                raise FakeAPIError(503)
            return gemini_json(cands)
        self.responder = responder
        self.assertTrue(M.calculate_match_scores(s, cands).fallback)
        self.install_client()                               # breaker mới (mô phỏng hết thời gian nghỉ)
        state["fail"] = False
        out = M.calculate_match_scores(s, cands)
        self.assertFalse(out.fallback)                      # B3: trước đây kết quả dự phòng nằm trong cache 10 phút
        self.assertEqual(out.source, "gemini")

    def test_unparseable_response_falls_back_with_reason(self):
        self.responder = lambda p: "xin lỗi tôi không biết"
        out = M.calculate_match_scores(shift(), [emp(1)])
        self.assertEqual((out.fallback, out.reason), (True, G.BAD_RESPONSE))

    def test_candidates_missing_from_gemini_are_scored_by_heuristic_not_dropped(self):
        s, cands = shift(), [emp(1), emp(2), emp(3)]
        body = json.loads(gemini_json(cands))
        body["results"] = [r for r in body["results"] if r["employee_id"] != 2]   # Gemini bỏ sót ứng viên 2
        body["results"][0]["reliability_score"] = "không phải số"                 # và hỏng tiêu chí của ứng viên 1
        self.responder = lambda p: json.dumps(body)
        out = M.calculate_match_scores(s, cands)
        self.assertEqual(sorted(r.employee_id for r in out.ranked), [1, 2, 3])
        by = {r.employee_id: r for r in out.ranked}
        self.assertEqual((by[1].source, by[2].source, by[3].source), ("heuristic", "heuristic", "gemini"))
        self.assertEqual((out.source, out.fallback, out.reason), ("mixed", True, G.PARTIAL))
        self.assertIn("2/3", out.message)
        # kết quả hỗn hợp không được cache
        M.calculate_match_scores(s, cands)
        self.assertEqual(len(self.calls), 2)

    def test_slow_gemini_times_out_and_flags(self):
        import time as _t
        self.responder = lambda p: _t.sleep(1.0) or gemini_json([emp(1)])
        self.install_client(deadline=0.5)
        out = M.calculate_match_scores(shift(), [emp(1)])
        self.assertEqual((out.fallback, out.reason), (True, G.TIMEOUT))

    def test_empty_candidates(self):
        out = M.calculate_match_scores(shift(), [])
        self.assertEqual((out.ranked, out.fallback), ([], False))


class QuickAndReady(Base):
    def test_quick_scores_never_call_gemini(self):
        res = M.quick_scores([(shift(shift_id=7), emp(3)), (shift(shift_id=8), emp(3, skill_level=1))])
        self.assertEqual([r.shift_id for r in res], [7, 8])
        self.assertTrue(all(r.source == "heuristic" and 0 <= r.match_score <= 100 for r in res))
        self.assertEqual(self.calls, [])
        self.assertGreater(res[0].match_score, res[1].match_score)

    def test_readiness_reports_configuration(self):
        r = M.readiness()
        self.assertEqual(r["status"], "ready")
        self.assertTrue(r["gemini_configured"])
        with mock.patch.dict(os.environ, {"GEMINI_API_KEY": ""}):
            self.assertFalse(M.readiness()["gemini_configured"])

    def test_default_model_name(self):
        with mock.patch.dict(os.environ, {"GEMINI_MODEL": ""}):
            self.assertEqual(M._get_model(), M.DEFAULT_MODEL)


if __name__ == "__main__":
    unittest.main()
