import unittest

from tests._helpers import shift, emp
import heuristic as H


class Reliability(unittest.TestCase):
    def test_penalty_is_proportional_not_absolute(self):
        veteran = H.reliability_score(emp(total_completed=50, total_cancelled=2, late_arrival_count=1))
        rookie = H.reliability_score(emp(total_completed=3, total_cancelled=2, late_arrival_count=1))
        self.assertGreater(veteran, 88)            # bản cũ: ~60 điểm cho người 50 ca hủy 2 (B11)
        self.assertLess(rookie, veteran - 20)

    def test_new_employee_is_neutral_not_perfect_or_terrible(self):
        r = H.reliability_score(emp(total_completed=0, total_cancelled=0, late_arrival_count=0))
        self.assertTrue(75 <= r <= 90, r)

    def test_bad_record_scores_low_and_bounded(self):
        r = H.reliability_score(emp(total_completed=5, total_cancelled=40, late_arrival_count=30))
        self.assertGreaterEqual(r, 10)
        self.assertLess(r, 30)


class TimeOfDay(unittest.TestCase):
    def test_night_preference_matches_night_shift(self):
        night = shift(start_time="2026-11-02T22:00:00", end_time="2026-11-03T05:00:00", shift_duration_hours=7)
        self.assertEqual(H.dominant_bucket(H.bucket_fractions(night)), "NIGHT")
        fit_night = H.time_fit_score(emp(preferred_time="NIGHT"), H.bucket_fractions(night))
        fit_morning = H.time_fit_score(emp(preferred_time="MORNING"), H.bucket_fractions(night))
        self.assertGreater(fit_night, 90)
        self.assertLess(fit_morning, 70)

    def test_shift_starting_after_midnight_is_not_morning(self):
        early = shift(start_time="2026-11-02T00:00:00", end_time="2026-11-02T05:00:00", shift_duration_hours=5)
        self.assertEqual(H.dominant_bucket(H.bucket_fractions(early)), "NIGHT")     # bản cũ coi 00:00-05:59 là "sáng"

    def test_fractions_sum_to_one_and_split_across_buckets(self):
        s = shift(start_time="2026-11-02T17:00:00", end_time="2026-11-02T22:00:00", shift_duration_hours=5)
        f = H.bucket_fractions(s)
        self.assertAlmostEqual(sum(f.values()), 1.0, places=6)
        self.assertAlmostEqual(f["AFTERNOON"], 0.2, places=2)     # 17-18
        self.assertAlmostEqual(f["EVENING"], 0.8, places=2)       # 18-22
        self.assertGreater(H.time_fit_score(emp(preferred_time="EVENING"), f),
                           H.time_fit_score(emp(preferred_time="AFTERNOON"), f))

    def test_any_preference_and_unparseable_times_are_neutral(self):
        self.assertEqual(H.time_fit_score(emp(preferred_time="ANY"), None), 85.0)
        self.assertEqual(H.time_fit_score(emp(preferred_time="MORNING"), None), 80.0)
        self.assertIsNone(H.bucket_fractions(shift(start_time="không phải ngày", end_time="")))

    def test_zulu_and_offset_timestamps_parse(self):
        self.assertIsNotNone(H.parse_dt("2026-11-02T07:00:00Z"))
        self.assertIsNotNone(H.parse_dt("2026-11-02T07:00:00+07:00"))
        self.assertIsNone(H.parse_dt(""))


class OtherCriteria(unittest.TestCase):
    def test_weights_sum_to_one(self):
        self.assertAlmostEqual(sum(H.WEIGHTS.values()), 1.0, places=9)

    def test_skill_fit_monotone(self):
        s = shift(required_level=3)
        scores = [H.skill_fit_score(emp(skill_level=l), s) for l in (1, 2, 3, 4, 5)]
        self.assertEqual(scores, sorted(scores))
        self.assertGreaterEqual(scores[2], 80)

    def test_workload_prefers_more_free_hours_relative_to_own_limit(self):
        light = H.workload_fit_score(emp(hours_worked_this_week=0, max_hours_per_week=40), 8)
        heavy = H.workload_fit_score(emp(hours_worked_this_week=24, max_hours_per_week=40), 8)
        over = H.workload_fit_score(emp(hours_worked_this_week=36, max_hours_per_week=40), 8)
        self.assertGreater(light, heavy)
        self.assertGreater(heavy, over)
        self.assertEqual(over, 30.0)

    def test_location_fit(self):
        s = shift(branch_id=1)
        self.assertEqual(H.location_fit_score(emp(branch_id=1), s), 100.0)
        self.assertEqual(H.location_fit_score(emp(branch_id=2), s), 60.0)
        self.assertEqual(H.location_fit_score(emp(branch_id=0), s), 70.0)

    def test_duration_derived_when_missing(self):
        s = shift(shift_duration_hours=0)
        self.assertEqual(H.shift_duration_hours(s), 8.0)
        self.assertEqual(H.shift_duration_hours(shift(shift_duration_hours=0, start_time="", end_time="")), 4.0)


class Ranking(unittest.TestCase):
    def test_result_has_all_fields_and_is_flagged_heuristic(self):
        r = H.heuristic_match_scores(shift(), [emp(1), emp(2, skill_level=5, total_cancelled=0, late_arrival_count=0)])
        self.assertEqual([x.employee_id for x in r], [2, 1])
        for s in r:
            self.assertEqual(s.source, "heuristic")
            self.assertTrue(0 <= s.score_low <= s.match_score <= s.score_high <= 100)
            self.assertTrue(0 <= s.confidence <= 100)
            for k in ("reliability", "skill_fit", "time_fit", "workload_fit", "location_fit", "explanation"):
                self.assertIn(k, s.breakdown)

    def test_scores_are_not_all_identical(self):
        r = H.heuristic_match_scores(shift(), [emp(1, skill_level=3), emp(2, skill_level=5, preferred_time="NIGHT"),
                                               emp(3, total_cancelled=20)])
        self.assertGreater(len({s.match_score for s in r}), 1)

    def test_guardrails_cap_unqualified_or_overloaded(self):
        low_skill = H.score_one(emp(skill_level=1), shift(required_level=4))
        overloaded = H.score_one(emp(hours_worked_this_week=38, max_hours_per_week=40), shift())
        self.assertLessEqual(low_skill.match_score, 40)
        self.assertLessEqual(overloaded.match_score, 35)

    def test_more_history_means_more_confidence_and_narrower_band(self):
        a = H.score_one(emp(total_completed=0, total_cancelled=0, late_arrival_count=0), shift())
        b = H.score_one(emp(total_completed=60, total_cancelled=1, late_arrival_count=1), shift())
        self.assertGreater(b.confidence, a.confidence)
        self.assertLess(b.score_high - b.score_low, a.score_high - a.score_low)

    def test_deterministic(self):
        cands = [emp(i, total_completed=i) for i in range(1, 6)]
        self.assertEqual([s.model_dump() for s in H.heuristic_match_scores(shift(), cands)],
                         [s.model_dump() for s in H.heuristic_match_scores(shift(), cands)])


if __name__ == "__main__":
    unittest.main()
