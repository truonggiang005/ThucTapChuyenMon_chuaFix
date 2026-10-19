"""
Xác thực nội bộ Spring -> Python bằng khóa dùng chung (header X-Internal-Key) - SEC-08, hỗ trợ XOAY VÒNG khóa (C6).

Quy trình xoay khóa không gián đoạn:
  1. Python: đặt INTERNAL_KEY=<khóa mới>, INTERNAL_KEY_PREVIOUS=<khóa cũ>, khởi động lại  (nhận CẢ HAI khóa)
  2. Spring: đặt AI_INTERNAL_KEY=<khóa mới>, khởi động lại
  3. Python: bỏ INTERNAL_KEY_PREVIOUS, khởi động lại                                       (chỉ còn khóa mới)

Module không phụ thuộc FastAPI để kiểm thử được độc lập.
"""

import hmac
import os
from typing import List, Mapping, Optional

_PLACEHOLDERS = {"", "change_me"}

OK = "ok"
UNCONFIGURED = "unconfigured"
UNAUTHORIZED = "unauthorized"


def configured_keys(env: Optional[Mapping[str, str]] = None) -> List[str]:
    """Các khóa hiện được chấp nhận (khóa hiện tại + khóa trước đó nếu đang xoay vòng). Bỏ giá trị mẫu/rỗng."""
    env = os.environ if env is None else env
    keys: List[str] = []
    for name in ("INTERNAL_KEY", "INTERNAL_KEY_PREVIOUS"):
        value = (env.get(name) or "").strip()
        if value and value.lower() not in _PLACEHOLDERS:
            keys.append(value)
    return keys


def check_key(provided: Optional[str], env: Optional[Mapping[str, str]] = None) -> str:
    """
    OK | UNCONFIGURED (server chưa có khóa hợp lệ -> HTTP 503) | UNAUTHORIZED (sai/thiếu khóa -> HTTP 401).
    So sánh theo thời gian không đổi với MỌI khóa để không lộ thông tin qua độ trễ.
    """
    keys = configured_keys(env)
    if not keys:
        return UNCONFIGURED
    if not provided:
        return UNAUTHORIZED
    matched = False
    for k in keys:
        if hmac.compare_digest(provided.encode("utf-8"), k.encode("utf-8")):
            matched = True
    return OK if matched else UNAUTHORIZED
