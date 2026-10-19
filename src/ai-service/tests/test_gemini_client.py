import time
import unittest

from tests._helpers import FakeAPIError
import gemini_client as G


class FakeClock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t

    def advance(self, s):
        self.t += s


def make_client(transport, key="k", deadline=5.0, clock=None, threshold=3, cooldown=30.0):
    clock = clock or FakeClock()
    breaker = G.CircuitBreaker(threshold=threshold, cooldown=cooldown, clock=clock)
    return G.GeminiClient(lambda: key, lambda: "m", transport=transport, breaker=breaker,
                          deadline_s=deadline, max_concurrency=2, clock=time.monotonic if clock is None else time.monotonic,
                          sleep=lambda s: None), breaker, clock


class Classification(unittest.TestCase):
    def test_http_codes(self):
        self.assertEqual(G.classify_exception(FakeAPIError(429)).code, G.RATE_LIMITED)
        self.assertEqual(G.classify_exception(FakeAPIError(0, "RESOURCE_EXHAUSTED")).code, G.RATE_LIMITED)
        self.assertEqual(G.classify_exception(FakeAPIError(401)).code, G.AUTH)
        self.assertEqual(G.classify_exception(FakeAPIError(403)).code, G.AUTH)
        self.assertEqual(G.classify_exception(FakeAPIError(404)).code, G.MODEL_NOT_FOUND)
        self.assertEqual(G.classify_exception(FakeAPIError(400)).code, G.BAD_REQUEST)
        e503 = G.classify_exception(FakeAPIError(503))
        self.assertEqual(e503.code, G.UNAVAILABLE)
        self.assertTrue(e503.retryable)

    def test_message_containing_the_word_quota_is_not_a_429(self):
        # bản cũ so chuỗi "quota" trong thông báo lỗi (B8)
        self.assertNotEqual(G.classify_exception(RuntimeError("the quota of my patience")).code, G.RATE_LIMITED)

    def test_network_and_timeout(self):
        self.assertEqual(G.classify_exception(TimeoutError()).code, G.TIMEOUT)
        self.assertEqual(G.classify_exception(ConnectionError()).code, G.UNAVAILABLE)
        self.assertEqual(G.classify_exception(ValueError("x")).code, G.OTHER)


class CircuitBreakerTest(unittest.TestCase):
    def test_transient_errors_need_threshold(self):
        clock = FakeClock()
        b = G.CircuitBreaker(threshold=3, cooldown=30, clock=clock)
        e = G.GeminiError(G.UNAVAILABLE, "x", retryable=True)
        b.record_failure(e); b.record_failure(e)
        self.assertEqual(b.state, "closed")
        b.record_failure(e)
        self.assertEqual(b.state, "open")
        self.assertFalse(b.allow())

    def test_rate_limit_opens_immediately_then_half_open_single_trial(self):
        clock = FakeClock()
        b = G.CircuitBreaker(threshold=3, cooldown=30, clock=clock)
        b.record_failure(G.GeminiError(G.RATE_LIMITED, "x", cooldown=60))
        self.assertEqual(b.state, "open")
        clock.advance(59); self.assertFalse(b.allow())
        clock.advance(2)
        self.assertEqual(b.state, "half_open")
        self.assertTrue(b.allow())          # đúng MỘT lời gọi thử
        self.assertFalse(b.allow())
        b.record_success()
        self.assertEqual(b.state, "closed")
        self.assertTrue(b.allow())

    def test_failed_trial_reopens(self):
        clock = FakeClock()
        b = G.CircuitBreaker(threshold=3, cooldown=30, clock=clock)
        b.record_failure(G.GeminiError(G.RATE_LIMITED, "x", cooldown=10))
        clock.advance(11)
        self.assertTrue(b.allow())
        b.record_failure(G.GeminiError(G.UNAVAILABLE, "x"))
        self.assertEqual(b.state, "open")


class ClientBehaviour(unittest.TestCase):
    def test_not_configured_does_not_call_transport(self):
        calls = []
        c, b, _ = make_client(lambda *a: calls.append(1) or "{}", key=None)
        with self.assertRaises(G.GeminiError) as cm:
            c.generate_json("p", {})
        self.assertEqual(cm.exception.code, G.NOT_CONFIGURED)
        self.assertEqual(calls, [])

    def test_success_returns_parsed(self):
        c, b, _ = make_client(lambda *a: '{"a": 1}')
        import json
        self.assertEqual(c.generate_json("p", {}, parse=json.loads), {"a": 1})

    def test_retries_transient_error_once(self):
        calls = {"n": 0}

        def transport(*a):
            calls["n"] += 1
            if calls["n"] == 1:
                raise FakeAPIError(503)
            return "ok"
        c, b, _ = make_client(transport, deadline=12)
        self.assertEqual(c.generate_json("p", {}), "ok")
        self.assertEqual(calls["n"], 2)

    def test_gives_up_after_one_retry(self):
        calls = {"n": 0}

        def transport(*a):
            calls["n"] += 1
            raise FakeAPIError(503)
        c, b, _ = make_client(transport, deadline=12)
        with self.assertRaises(G.GeminiError) as cm:
            c.generate_json("p", {})
        self.assertEqual(cm.exception.code, G.UNAVAILABLE)
        self.assertEqual(calls["n"], 2)

    def test_rate_limit_is_not_retried_and_opens_circuit_so_next_call_skips_gemini(self):
        calls = {"n": 0}

        def transport(*a):
            calls["n"] += 1
            raise FakeAPIError(429)
        c, b, _ = make_client(transport)
        with self.assertRaises(G.GeminiError) as cm:
            c.generate_json("p", {})
        self.assertEqual(cm.exception.code, G.RATE_LIMITED)
        self.assertEqual(calls["n"], 1)
        with self.assertRaises(G.GeminiError) as cm2:
            c.generate_json("p", {})
        self.assertEqual(cm2.exception.code, G.RATE_LIMITED)     # giữ nguyên lý do gốc
        self.assertIn("tạm ngưng", cm2.exception.message)
        self.assertEqual(calls["n"], 1)                           # KHÔNG gọi Gemini lần nữa (B8)
        self.assertEqual(c.status()["circuit"], "open")

    def test_deadline_is_enforced(self):
        c, b, _ = make_client(lambda *a: time.sleep(1.0) or "late", deadline=0.5)
        t0 = time.monotonic()
        with self.assertRaises(G.GeminiError) as cm:
            c.generate_json("p", {})
        self.assertEqual(cm.exception.code, G.TIMEOUT)
        self.assertLess(time.monotonic() - t0, 0.95)

    def test_bad_response_counts_as_failure_and_eventually_opens_circuit(self):
        import json

        def parse(text):
            try:
                return json.loads(text)
            except ValueError:
                raise G.GeminiError(G.BAD_RESPONSE, "rác")
        c, b, _ = make_client(lambda *a: "not json", threshold=3)
        for _ in range(3):
            with self.assertRaises(G.GeminiError):
                c.generate_json("p", {}, parse=parse)
        self.assertEqual(c.status()["circuit"], "open")

    def test_success_resets_failure_count(self):
        seq = iter([FakeAPIError(500), "ok", FakeAPIError(500), "ok"])
        # mỗi lần generate_json: lần 1 lỗi 500 (retry) -> thành công
        def transport(*a):
            v = next(seq)
            if isinstance(v, Exception):
                raise v
            return v
        c, b, _ = make_client(transport, deadline=12)
        self.assertEqual(c.generate_json("p", {}), "ok")
        self.assertEqual(c.generate_json("p", {}), "ok")
        self.assertEqual(c.status()["circuit"], "closed")

    def test_status_shape(self):
        c, b, _ = make_client(lambda *a: "ok")
        st = c.status()
        self.assertEqual(set(st), {"gemini_configured", "model", "circuit", "circuit_retry_in_seconds", "last_error"})
        self.assertTrue(st["gemini_configured"])


if __name__ == "__main__":
    unittest.main()
