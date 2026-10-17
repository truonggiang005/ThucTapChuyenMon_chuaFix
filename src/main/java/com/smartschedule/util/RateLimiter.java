package com.smartschedule.util;

import com.smartschedule.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Giới hạn tần suất theo khóa (cửa sổ trượt, lưu trong bộ nhớ của 1 instance).
 * Dùng để chặn đoán mật khẩu và chống đốt quota AI (SEC-09). Nếu chạy nhiều instance cần Redis/bucket4j.
 */
@Component
public class RateLimiter {

    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public void check(String key, int maxRequests, Duration window) {
        long now = System.currentTimeMillis();
        long windowStart = now - window.toMillis();
        Deque<Long> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() < windowStart) {
                deque.pollFirst();
            }
            if (deque.size() >= maxRequests) {
                throw new TooManyRequestsException("Bạn thao tác quá nhanh. Vui lòng thử lại sau ít phút.");
            }
            deque.addLast(now);
        }
        if (hits.size() > 10_000) {   // dọn khóa không còn hoạt động
            hits.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || e.getValue().peekLast() < windowStart;
                }
            });
        }
    }
}
