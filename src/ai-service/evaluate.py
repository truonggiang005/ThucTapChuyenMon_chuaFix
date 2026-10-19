"""
Đánh giá chất lượng bộ chấm điểm (E7) — chạy thủ công, KHÔNG nằm trong luồng chạy của ứng dụng.

    python evaluate.py                 # đánh giá bộ quy tắc (không cần mạng)
    python evaluate.py --gemini        # thêm Gemini (cần GEMINI_API_KEY trong .env); đo cả độ trễ
    python evaluate.py --gemini --out ket_qua_danh_gia.md

Hệ thống thuộc HƯỚNG 3 (LLM qua API): KHÔNG có model tự huấn luyện nên không có R²/MAE trên nhãn thực tế.
Chưa có dữ liệu "kết quả thật" (ai làm tốt ca nào) để làm nhãn. Thay vào đó script đo những gì đo được trung thực:

  1. Độ chính xác xếp hạng theo CẶP trên bộ tình huống có nhãn tay (mỗi cặp: ứng viên A nên được xếp trên B vì lý do rõ ràng).
  2. (--gemini) Độ đồng thuận Gemini ↔ quy tắc: MAE điểm tổng và tương quan hạng Spearman trên cùng danh sách ứng viên.
  3. (--gemini) Độ trễ p50/p95 và tỉ lệ phải dùng dự phòng (fallback).
  4. Độ tin cậy/khoảng dao động trung bình, để so khi lượng dữ liệu lịch sử tăng.

Kết quả là bằng chứng về TÍNH HỢP LÝ của xếp hạng, không phải độ chính xác dự đoán trong thực tế. Khi hệ thống có dữ liệu thật
(ca đã hoàn thành, đánh giá của quản lý) hãy dùng làm nhãn để tính MAE/R² thật sự.
"""

import argparse
import statistics
import sys
import time
from typing import Dict, List, Tuple

from schemas import EmployeeData, ShiftData
import heuristic as H


def _shift(**kw) -> ShiftData:
    base = dict(shift_id=1, branch_id=1, required_skill_id=1, required_level=3,
                start_time="2026-11-02T07:00:00", end_time="2026-11-02T15:00:00", shift_duration_hours=8.0)
    base.update(kw)
    return ShiftData(**base)


def _emp(eid: int, **kw) -> EmployeeData:
    base = dict(employee_id=eid, full_name=f"NV{eid}", skill_level=4, total_completed=30, total_cancelled=1,
                late_arrival_count=1, preferred_time="MORNING", hours_worked_this_week=8.0,
                max_hours_per_week=40, branch_id=1)
    base.update(kw)
    return EmployeeData(**base)


# (mô tả, ca, ứng viên nên xếp TRÊN, ứng viên nên xếp DƯỚI)
PAIRS: List[Tuple[str, ShiftData, EmployeeData, EmployeeData]] = [
    ("Ít hủy/muộn hơn (cùng số ca)", _shift(), _emp(1, total_cancelled=0, late_arrival_count=0), _emp(2, total_cancelled=8, late_arrival_count=6)),
    ("Tỉ lệ hủy thấp dù số tuyệt đối bằng nhau", _shift(), _emp(1, total_completed=80, total_cancelled=3), _emp(2, total_completed=6, total_cancelled=3)),
    ("Kỹ năng cao hơn yêu cầu", _shift(required_level=3), _emp(1, skill_level=5), _emp(2, skill_level=3)),
    ("Thiếu kỹ năng", _shift(required_level=4), _emp(1, skill_level=4), _emp(2, skill_level=2)),
    ("Khung giờ ưa thích trùng ca sáng", _shift(), _emp(1, preferred_time="MORNING"), _emp(2, preferred_time="NIGHT")),
    ("Ca đêm hợp người ưa NIGHT", _shift(start_time="2026-11-02T22:00:00", end_time="2026-11-03T05:00:00", shift_duration_hours=7),
     _emp(1, preferred_time="NIGHT"), _emp(2, preferred_time="MORNING")),
    ("Ca 00:00-05:00 không phải ca sáng", _shift(start_time="2026-11-02T00:00:00", end_time="2026-11-02T05:00:00", shift_duration_hours=5),
     _emp(1, preferred_time="NIGHT"), _emp(2, preferred_time="MORNING")),
    ("Ca tối hợp người ưa tối hơn người ưa sáng", _shift(start_time="2026-11-02T18:00:00", end_time="2026-11-02T22:00:00", shift_duration_hours=4),
     _emp(1, preferred_time="EVENING"), _emp(2, preferred_time="MORNING")),
    ("Còn nhiều giờ trống hơn", _shift(), _emp(1, hours_worked_this_week=0), _emp(2, hours_worked_this_week=30)),
    ("Sắp chạm trần giờ của chính họ", _shift(), _emp(1, hours_worked_this_week=10, max_hours_per_week=40), _emp(2, hours_worked_this_week=30, max_hours_per_week=40)),
    ("Cùng chi nhánh với ca", _shift(branch_id=1), _emp(1, branch_id=1), _emp(2, branch_id=2)),
    ("Người dày dạn đáng tin hơn người chưa có dữ liệu (cùng tỉ lệ tốt)", _shift(), _emp(1, total_completed=60, total_cancelled=1, late_arrival_count=1), _emp(2, total_completed=0, total_cancelled=0, late_arrival_count=0)),
]

# Danh sách ứng viên đa dạng để đo độ đồng thuận Gemini ↔ quy tắc
_LISTS: List[Tuple[ShiftData, List[EmployeeData]]] = [
    (_shift(shift_id=11), [_emp(1), _emp(2, skill_level=5, total_cancelled=0, late_arrival_count=0),
                           _emp(3, total_cancelled=10, late_arrival_count=8), _emp(4, preferred_time="NIGHT"),
                           _emp(5, hours_worked_this_week=30), _emp(6, branch_id=2, total_completed=2)]),
    (_shift(shift_id=12, start_time="2026-11-03T18:00:00", end_time="2026-11-03T23:00:00", shift_duration_hours=5, required_level=2),
     [_emp(1, preferred_time="EVENING"), _emp(2, preferred_time="MORNING"), _emp(3, skill_level=2, preferred_time="EVENING"),
      _emp(4, preferred_time="ANY", total_completed=0, total_cancelled=0, late_arrival_count=0),
      _emp(5, total_cancelled=6, late_arrival_count=5)]),
    (_shift(shift_id=13, start_time="2026-11-04T22:00:00", end_time="2026-11-05T06:00:00", shift_duration_hours=8, required_level=1),
     [_emp(1, preferred_time="NIGHT"), _emp(2, preferred_time="NIGHT", total_cancelled=9), _emp(3, preferred_time="MORNING"),
      _emp(4, preferred_time="AFTERNOON", hours_worked_this_week=25), _emp(5, preferred_time="ANY")]),
]


def _ranks(values: List[float]) -> List[float]:
    order = sorted(range(len(values)), key=lambda i: values[i])
    ranks = [0.0] * len(values)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and values[order[j + 1]] == values[order[i]]:
            j += 1
        for k in range(i, j + 1):
            ranks[order[k]] = (i + j) / 2.0 + 1
        i = j + 1
    return ranks


def spearman(a: List[float], b: List[float]) -> float:
    ra, rb = _ranks(a), _ranks(b)
    ma, mb = statistics.fmean(ra), statistics.fmean(rb)
    num = sum((x - ma) * (y - mb) for x, y in zip(ra, rb))
    den = (sum((x - ma) ** 2 for x in ra) * sum((y - mb) ** 2 for y in rb)) ** 0.5
    return num / den if den else 0.0


def pairwise_heuristic() -> Tuple[int, int, List[str]]:
    ok, failed = 0, []
    for desc, s, better, worse in PAIRS:
        rb = H.score_one(better, s).match_score
        rw = H.score_one(worse, s).match_score
        if rb > rw:
            ok += 1
        else:
            failed.append(f"{desc}: {rb} <= {rw}")
    return ok, len(PAIRS), failed


def pairwise_gemini(model) -> Tuple[int, int, List[str], List[float], int]:
    ok, failed, latencies, fallbacks = 0, [], [], 0
    for desc, s, better, worse in PAIRS:
        t0 = time.monotonic()
        out = model.calculate_match_scores(s, [better, worse])
        latencies.append(time.monotonic() - t0)
        if out.fallback:
            fallbacks += 1
        by = {r.employee_id: r.match_score for r in out.ranked}
        if by[better.employee_id] > by[worse.employee_id]:
            ok += 1
        else:
            failed.append(f"{desc}: {by[better.employee_id]} <= {by[worse.employee_id]}")
    return ok, len(PAIRS), failed, latencies, fallbacks


def agreement(model) -> Dict[str, float]:
    maes, rhos, confs, widths = [], [], [], []
    latencies, fallbacks = [], 0
    for s, cands in _LISTS:
        h = {r.employee_id: r.match_score for r in H.heuristic_match_scores(s, cands)}
        model.clear_cache()
        t0 = time.monotonic()
        out = model.calculate_match_scores(s, cands)
        latencies.append(time.monotonic() - t0)
        if out.fallback:
            fallbacks += 1
            continue
        g = {r.employee_id: r.match_score for r in out.ranked}
        ids = sorted(g)
        maes.append(statistics.fmean(abs(g[i] - h[i]) for i in ids))
        rhos.append(spearman([g[i] for i in ids], [h[i] for i in ids]))
        confs.extend(r.confidence for r in out.ranked)
        widths.extend(r.score_high - r.score_low for r in out.ranked)
    res: Dict[str, float] = {"lists": float(len(_LISTS)), "fallbacks": float(fallbacks)}
    if maes:
        res.update(mae=statistics.fmean(maes), spearman=statistics.fmean(rhos),
                   confidence=statistics.fmean(confs), band_width=statistics.fmean(widths))
    res["latency_p50"] = statistics.median(latencies)
    res["latency_max"] = max(latencies)
    return res


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--gemini", action="store_true", help="đánh giá cả Gemini (cần GEMINI_API_KEY, tốn quota)")
    ap.add_argument("--out", help="ghi báo cáo Markdown ra file")
    args = ap.parse_args()

    lines: List[str] = ["# Kết quả đánh giá bộ chấm điểm", "",
                        "> Hướng 3 (LLM qua API): không có model tự huấn luyện, không có nhãn thực tế → không báo R²/MAE trên thực tế.",
                        "> Các chỉ số dưới đây đo tính hợp lý của xếp hạng và độ đồng thuận, không phải độ chính xác dự đoán.", ""]

    ok, n, failed = pairwise_heuristic()
    lines += ["## 1. Bộ quy tắc: độ chính xác xếp hạng theo cặp (nhãn tay)", "",
              f"- Đúng **{ok}/{n}** cặp ({100.0 * ok / n:.0f}%)"]
    lines += [f"- Sai: {f}" for f in failed]
    lines.append("")

    if args.gemini:
        import model as M   # nạp muộn để chế độ mặc định không cần SDK/khóa
        if not M._get_api_key():
            lines += ["## 2. Gemini", "", "- Bỏ qua: chưa cấu hình GEMINI_API_KEY."]
        else:
            M.clear_cache()
            gok, gn, gfailed, lat, fb = pairwise_gemini(M)
            lines += ["## 2. Gemini: độ chính xác xếp hạng theo cặp", "",
                      f"- Đúng **{gok}/{gn}** cặp ({100.0 * gok / gn:.0f}%); phải dùng dự phòng: {fb}/{gn} lần"]
            lines += [f"- Sai: {f}" for f in gfailed]
            if lat:
                lines.append(f"- Độ trễ mỗi lần chấm: p50 {statistics.median(lat):.2f}s, tối đa {max(lat):.2f}s")
            lines.append("")
            ag = agreement(M)
            lines += ["## 3. Độ đồng thuận Gemini ↔ quy tắc (danh sách 5-6 ứng viên)", ""]
            if "mae" in ag:
                lines += [f"- MAE điểm tổng: **{ag['mae']:.1f}** điểm",
                          f"- Tương quan hạng Spearman: **{ag['spearman']:.2f}**",
                          f"- Độ tin cậy trung bình: {ag['confidence']:.0f}/100; độ rộng khoảng dao động trung bình: {ag['band_width']:.1f} điểm"]
            lines += [f"- Danh sách phải dùng dự phòng: {int(ag['fallbacks'])}/{int(ag['lists'])}",
                      f"- Độ trễ: p50 {ag['latency_p50']:.2f}s, tối đa {ag['latency_max']:.2f}s", ""]

    report = "\n".join(lines)
    print(report)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write(report + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
