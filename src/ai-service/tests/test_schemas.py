import unittest
from pydantic import ValidationError

from tests import _helpers  # noqa: F401
from schemas import EmployeeData, ShiftData, MatchRequest, MAX_CANDIDATES, normalize_preferred_time


class SchemaTolerance(unittest.TestCase):
    def test_null_optional_fields_use_defaults(self):
        e = EmployeeData(employee_id=5, full_name=None, skill_level=None, total_completed=None, total_cancelled=None,
                         late_arrival_count=None, preferred_time=None, hours_worked_this_week=None,
                         max_hours_per_week=None, branch_id=None)
        self.assertEqual((e.skill_level, e.total_completed, e.total_cancelled, e.late_arrival_count), (1, 0, 0, 0))
        self.assertEqual((e.preferred_time, e.hours_worked_this_week, e.max_hours_per_week, e.branch_id),
                         ("ANY", 0.0, 40, 0))
        self.assertEqual(e.full_name, "")

    def test_missing_fields_use_defaults(self):
        e = EmployeeData(employee_id=5)
        self.assertEqual(e.max_hours_per_week, 40)

    def test_out_of_range_values_are_clamped_not_rejected(self):
        e = EmployeeData(employee_id=1, skill_level=9, total_cancelled=-3, hours_worked_this_week=500, max_hours_per_week=0)
        self.assertEqual(e.skill_level, 5)
        self.assertEqual(e.total_cancelled, 0)
        self.assertEqual(e.hours_worked_this_week, 200.0)
        self.assertEqual(e.max_hours_per_week, 1)

    def test_string_numbers_are_coerced(self):
        e = EmployeeData(employee_id="12", skill_level="3", hours_worked_this_week="7.5")
        self.assertEqual((e.employee_id, e.skill_level, e.hours_worked_this_week), (12, 3, 7.5))

    def test_shift_null_branch_skill_level_and_long_duration(self):
        s = ShiftData(shift_id=1, branch_id=None, required_skill_id=None, required_level=None,
                      start_time="2026-11-02T07:00", end_time="2026-11-02T15:00", shift_duration_hours=60)
        self.assertEqual((s.branch_id, s.required_skill_id, s.required_level), (0, 0, 1))
        self.assertEqual(s.shift_duration_hours, 60)      # trước đây >48 bị 422 -> điểm 75% giả (B10)
        self.assertEqual(ShiftData(shift_id=1, shift_duration_hours=9999).shift_duration_hours, 168.0)

    def test_shift_and_employee_ids_are_still_required(self):
        with self.assertRaises(ValidationError):
            ShiftData(start_time="x")
        with self.assertRaises(ValidationError):
            EmployeeData(full_name="x")

    def test_candidate_count_bounds(self):
        s = {"shift_id": 1}
        with self.assertRaises(ValidationError):
            MatchRequest(shift=s, candidates=[])
        with self.assertRaises(ValidationError):
            MatchRequest(shift=s, candidates=[{"employee_id": i} for i in range(MAX_CANDIDATES + 1)])
        self.assertEqual(len(MatchRequest(shift=s, candidates=[{"employee_id": i} for i in range(MAX_CANDIDATES)]).candidates),
                         MAX_CANDIDATES)

    def test_preferred_time_normalisation(self):
        self.assertEqual(normalize_preferred_time("night"), "NIGHT")
        self.assertEqual(normalize_preferred_time("Sáng"), "MORNING")
        self.assertEqual(normalize_preferred_time("tối"), "EVENING")
        self.assertEqual(normalize_preferred_time("whatever"), "ANY")
        self.assertEqual(normalize_preferred_time(None), "ANY")


if __name__ == "__main__":
    unittest.main()
