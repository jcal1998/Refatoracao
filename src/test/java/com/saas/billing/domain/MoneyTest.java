package com.saas.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void arithmeticIsExactUnlikeDouble() {
        // Em double, 0.1 + 0.2 == 0.30000000000000004.
        assertThat(Money.of("0.10").plus(Money.of("0.20"))).isEqualTo(Money.of("0.30"));
    }

    @Test
    void operationsReturnNewInstancesAndNeverMutate() {
        Money price = Money.of("100.00");

        Money discounted = price.minus(Money.of("30.00"));

        assertThat(discounted).isEqualTo(Money.of("70.00"));
        assertThat(price).isEqualTo(Money.of("100.00"));
    }

    @Test
    void intermediateValuesKeepFullPrecisionUntilRounded() {
        Money price = Money.of("89.00").times(new BigDecimal("1.025"));

        assertThat(price.amount()).isEqualByComparingTo("91.225");
        assertThat(price.rounded()).isEqualTo(Money.of("91.23"));
    }

    @Test
    void roundsHalfUpToCents() {
        assertThat(Money.of("10.004").rounded()).isEqualTo(Money.of("10.00"));
        assertThat(Money.of("10.005").rounded()).isEqualTo(Money.of("10.01"));
        assertThat(Money.of("10.015").rounded()).isEqualTo(Money.of("10.02"));
    }

    @Test
    void mayBecomeNegativeDuringCalculation() {
        Money price = Money.of("20.00").minus(Money.of("30.00"));

        assertThat(price.isNegative()).isTrue();
        assertThat(Money.ZERO.isNegative()).isFalse();
    }

    @Test
    void equalityIgnoresScale() {
        assertThat(Money.of("10.0")).isEqualTo(Money.of("10.00"));
        assertThat(Money.of("10.0")).hasSameHashCodeAs(Money.of("10.00"));
        assertThat(Money.of("10.00")).isNotEqualTo(Money.of("10.01"));
    }

    @Test
    void formatsWithCurrencyAndTwoDecimals() {
        assertThat(Money.of("72.1").format()).isEqualTo("R$ 72.10");
        assertThat(Money.of("62.995").format()).isEqualTo("R$ 63.00");
        assertThat(Money.ZERO.format()).isEqualTo("R$ 0.00");
    }

    @Test
    void rejectsNullAmount() {
        assertThatThrownBy(() -> new Money(null)).isInstanceOf(NullPointerException.class);
    }
}
