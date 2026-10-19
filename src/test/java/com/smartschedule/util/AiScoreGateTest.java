package com.smartschedule.util;

import com.smartschedule.util.AiScoreGate.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiScoreGateTest {

    @Test
    void geminiScoresProceedNormally() {
        assertEquals(Decision.PROCEED, AiScoreGate.decide(true, false, true));
        assertEquals(Decision.PROCEED, AiScoreGate.decide(true, false, false));
    }

    @Test
    void formulaScoresProceedWhenAllowed() {
        // Gemini hết quota → điểm công thức, vẫn tự gán
        assertEquals(Decision.PROCEED_BY_FORMULA, AiScoreGate.decide(true, true, true));
    }

    @Test
    void formulaScoresSkippedWhenDisabled() {
        assertEquals(Decision.SKIP, AiScoreGate.decide(true, true, false));
    }

    @Test
    void noScoresAtAllAlwaysSkips() {
        // AI service không chạy / không kết nối: không có điểm nào để gán, bất kể cấu hình
        assertEquals(Decision.SKIP, AiScoreGate.decide(false, true, true));
        assertEquals(Decision.SKIP, AiScoreGate.decide(false, false, true));
        assertEquals(Decision.SKIP, AiScoreGate.decide(false, true, false));
    }
}
