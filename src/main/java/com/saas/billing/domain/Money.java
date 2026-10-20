package com.saas.billing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Valor monetário imutável, em reais.
 *
 * <p>As operações preservam a precisão decimal exata (nada de {@code double}) e o
 * arredondamento para centavos acontece uma única vez, em {@link #rounded()}, para
 * que descontos encadeados não acumulem erro de centavo a cada passo. O valor pode
 * ficar negativo durante o cálculo (ex.: cupom maior que o preço); quem decide
 * zerá-lo é a regra de negócio, não o Value Object.
 */
public record Money(BigDecimal amount) {

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    private static final int CENTS = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public Money {
        Objects.requireNonNull(amount, "amount");
    }

    public static Money of(String amount) {
        return new Money(new BigDecimal(amount));
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(factor));
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public Money rounded() {
        return new Money(amount.setScale(CENTS, ROUNDING));
    }

    /** Formato usado em logs e e-mails: {@code R$ 72.10}. */
    public String format() {
        return "R$ " + rounded().amount.toPlainString();
    }

    /** Valores iguais são iguais independentemente da escala ({@code 10.0} == {@code 10.00}). */
    @Override
    public boolean equals(Object other) {
        return other instanceof Money money && amount.compareTo(money.amount) == 0;
    }

    @Override
    public int hashCode() {
        return amount.stripTrailingZeros().hashCode();
    }
}
