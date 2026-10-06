package com.academy.paybridge.shared.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    private static final Currency NGN = Currency.getInstance("NGN");

    @Test
    void convertsMajorUnitsToMinorUnitsExactly() {
        assertThat(Money.ofMajor(new BigDecimal("5000.50"), NGN).minorUnits()).isEqualTo(500_050L);
        assertThat(Money.ofMajor(new BigDecimal("5000"), NGN).minorUnits()).isEqualTo(500_000L);
        assertThat(Money.ofMajor(new BigDecimal("0.10"), NGN).minorUnits()).isEqualTo(10L);
    }

    @Test
    void roundTripsBackToMajorUnits() {
        assertThat(new Money(500_050L, NGN).toMajor()).isEqualByComparingTo("5000.50");
    }

    @Test
    void rejectsMoreDecimalPlacesThanTheCurrencyAllows() {
        assertThatThrownBy(() -> Money.ofMajor(new BigDecimal("10.005"), NGN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAmountsTooLargeForALong() {
        assertThatThrownBy(() -> Money.ofMajor(new BigDecimal("99999999999999999999.00"), NGN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isPositiveOnlyForAmountsAboveZero() {
        assertThat(new Money(1, NGN).isPositive()).isTrue();
        assertThat(new Money(0, NGN).isPositive()).isFalse();
        assertThat(new Money(-5, NGN).isPositive()).isFalse();
    }
}
