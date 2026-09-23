package com.example.marketing.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFormTest {

    @Test
    @DisplayName("空值与未设置都按 GLOBAL 解析，且不算「形态名写错」")
    void blankMeansGlobal() {
        assertEquals("GLOBAL", ConfigForm.resolve(null));
        assertEquals("GLOBAL", ConfigForm.resolve(""));
        assertEquals("GLOBAL", ConfigForm.resolve("   "));
        assertFalse(ConfigForm.unrecognized(""));
        assertFalse(ConfigForm.unrecognized(null));
    }

    @Test
    @DisplayName("大小写与空白无关；拼错的形态名退回 GLOBAL 并被标记")
    void caseInsensitiveAndTypoDetected() {
        assertEquals("LITE", ConfigForm.resolve(" lite "));
        assertEquals("FULL", ConfigForm.resolve("FULL"));
        assertTrue(ConfigForm.unrecognized("PREVIEW"));
        assertEquals("GLOBAL", ConfigForm.resolve("PREVIEW"));
    }
}
