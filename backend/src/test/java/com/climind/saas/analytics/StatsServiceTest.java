package com.climind.saas.analytics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class StatsServiceTest {

    @Test
    void pasosRedondosParaLaDistribucionDeImportes() {
        assertThat(StatsService.niceStep(new BigDecimal("350000"))).isEqualByComparingTo("50000");
        assertThat(StatsService.niceStep(new BigDecimal("1000"))).isEqualByComparingTo("200");
        assertThat(StatsService.niceStep(new BigDecimal("123456.78"))).isEqualByComparingTo("20000");
        assertThat(StatsService.niceStep(BigDecimal.ZERO)).isEqualByComparingTo("1000");
        // Sin notación científica (se serializa como "50000", no "5E+4").
        assertThat(StatsService.niceStep(new BigDecimal("350000")).scale()).isGreaterThanOrEqualTo(0);
        assertThat(StatsService.niceStep(new BigDecimal("350000")).toString()).isEqualTo("50000");
    }
}
