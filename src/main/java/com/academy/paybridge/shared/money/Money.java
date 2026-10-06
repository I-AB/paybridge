package com.academy.paybridge.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money stored as a whole number of minor units (kobo for NGN, cents for USD).
 *
 * <p>Money is never stored as a {@code double}: binary floating point cannot represent values
 * such as 0.10 exactly, so rounding errors creep in. A {@code long} of minor units is exact.
 */
public record Money(long minorUnits, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    /**
     * Converts a decimal amount in major units (for example 5000.50 naira) into Money.
     *
     * @throws IllegalArgumentException if the amount has more decimal places than the currency
     *                                  allows, is too large, or the currency has no minor unit
     */
    public static Money ofMajor(BigDecimal major, Currency currency) {
        Objects.requireNonNull(major, "major");
        Objects.requireNonNull(currency, "currency");
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0) {
            throw new IllegalArgumentException("Currency " + currency.getCurrencyCode() + " is not supported");
        }
        try {
            BigDecimal scaled = major.setScale(digits, RoundingMode.UNNECESSARY);
            return new Money(scaled.movePointRight(digits).longValueExact(), currency);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Amount must have at most " + digits + " decimal places and fit the supported range", e);
        }
    }

    /** The amount in major units, for example 5000.50. */
    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits());
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }
}
