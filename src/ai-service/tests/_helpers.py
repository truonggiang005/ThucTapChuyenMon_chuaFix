"""Tiện ích dùng chung cho test (đưa thư mục ai-service vào sys.path, dữ liệu mẫu)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from schemas import EmployeeData, ShiftData  # noqa: E402


def shift(**kw):
    base = dict(shift_id=1, branch_id=1, required_skill_id=1, required_level=3,
                start_time="2026-11-02T07:00:00", end_time="2026-11-02T15:00:00", shift_duration_hours=8.0)
    base.update(kw)
    return ShiftData(**base)


def emp(eid=1, **kw):
    base = dict(employee_id=eid, full_name=f"NV {eid}", skill_level=4, total_completed=30, total_cancelled=1,
                late_arrival_count=1, preferred_time="MORNING", hours_worked_this_week=8.0,
                max_hours_per_week=40, branch_id=1)
    base.update(kw)
    return EmployeeData(**base)


class FakeAPIError(Exception):
    """Giống google.genai.errors.APIError: có .code (HTTP) và .status."""

    def __init__(self, code, status=""):
        super().__init__(f"{code} {status}")
        self.code = code
        self.status = status
