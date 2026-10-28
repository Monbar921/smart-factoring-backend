package ru.smart.factoring.backend.factoring.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpdNumberTest {

    @Test
    void addsWordUpdOnlyWhenNumberHasNoIt() {
        assertThat(UpdNumber.label("U-1004")).isEqualTo("УПД U-1004");
        assertThat(UpdNumber.label("УПД-0255")).isEqualTo("УПД-0255");
        assertThat(UpdNumber.labelWithNo("U-1004")).isEqualTo("УПД № U-1004");
        assertThat(UpdNumber.labelWithNo("УПД-0255")).isEqualTo("УПД-0255");
        assertThat(UpdNumber.withNo("U-1004")).isEqualTo("№ U-1004");
        assertThat(UpdNumber.withNo("УПД-0255")).isEqualTo("УПД-0255");
    }
}
