"""Cache TTL có kích thước tối đa (LRU), an toàn khi nhiều luồng dùng chung. Không cần thư viện ngoài."""

import threading
import time
from collections import OrderedDict
from typing import Any, Callable, Optional


class TTLCache:
    def __init__(self, maxsize: int, ttl: float, clock: Callable[[], float] = time.time):
        self.maxsize = maxsize
        self.ttl = ttl
        self._clock = clock
        self._lock = threading.Lock()
        self._data: "OrderedDict[str, tuple]" = OrderedDict()

    def get(self, key: str) -> Optional[Any]:
        with self._lock:
            item = self._data.get(key)
            if item is None:
                return None
            expires_at, value = item
            if self._clock() >= expires_at:
                del self._data[key]
                return None
            self._data.move_to_end(key)
            return value

    def set(self, key: str, value: Any) -> None:
        with self._lock:
            self._data[key] = (self._clock() + self.ttl, value)
            self._data.move_to_end(key)
            if len(self._data) > self.maxsize:       # dọn mục hết hạn trước, rồi loại mục cũ nhất
                now = self._clock()
                for k in [k for k, (exp, _) in self._data.items() if now >= exp]:
                    del self._data[k]
            while len(self._data) > self.maxsize:
                self._data.popitem(last=False)

    def clear(self) -> None:
        with self._lock:
            self._data.clear()

    def __len__(self) -> int:
        with self._lock:
            return len(self._data)
