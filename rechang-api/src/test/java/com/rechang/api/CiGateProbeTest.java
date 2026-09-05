package com.rechang.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** CI 门禁演练用（票 #30035）：验证失败用例能让流水线标红——验证后立即删除 */
class CiGateProbeTest {

    @Test
    void deliberateFailureForGateProbe() {
        assertThat(true).isFalse();
    }
}
