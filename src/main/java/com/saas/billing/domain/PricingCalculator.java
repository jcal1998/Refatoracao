package com.saas.billing.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Regras de precificação da assinatura, sem rede, banco ou relógio: mesma
 * entrada, mesma saída. A ordem das regras faz parte do negócio (ex.: a Black
 * Friday é abatida depois do desconto de ONG, e a multa incide sobre o preço já
 * com descontos); o preço só é zerado e arredondado no final.
 */
public final class PricingCalculator {

    private static final BigDecimal TWO_YEARS_LOYALTY_DISCOUNT = new BigDecimal("0.15");
    private static final BigDecimal ONE_YEAR_LOYALTY_DISCOUNT = new BigDecimal("0.10");
    private static final BigDecimal NON_PROFIT_FACTOR = new BigDecimal("0.70");
    private static final Money BLACK_FRIDAY_DISCOUNT = Money.of("30.00");
    private static final BigDecimal WELCOME_FACTOR = new BigDecimal("0.90");
    private static final BigDecimal LATE_PENALTY_RATE = new BigDecimal("0.02");
    private static final BigDecimal DAILY_INTEREST_RATE = new BigDecimal("0.001");

    public Money calculate(BillingCommand command, LocalDate today) {
        int monthsActive = monthsActive(command.signupDate(), today);
        Money price = applyLoyaltyDiscount(command.basePrice(), monthsActive);
        price = applyNonProfitDiscount(price, command.nonProfit());
        price = applyPromoCode(price, command.promoCode(), monthsActive, today);
        price = applyOverdueCharges(price, command.overdueDays());
        return (price.isNegative() ? Money.ZERO : price).rounded();
    }

    /** Diferença em meses de calendário, ignorando o dia: 30/09 a 01/10 já conta 1 mês. */
    private static int monthsActive(LocalDate signupDate, LocalDate today) {
        return (today.getYear() - signupDate.getYear()) * 12 + (today.getMonthValue() - signupDate.getMonthValue());
    }

    private static Money applyLoyaltyDiscount(Money basePrice, int monthsActive) {
        if (monthsActive > 24) {
            return basePrice.minus(basePrice.times(TWO_YEARS_LOYALTY_DISCOUNT));
        }
        if (monthsActive > 12) {
            return basePrice.minus(basePrice.times(ONE_YEAR_LOYALTY_DISCOUNT));
        }
        return basePrice;
    }

    private static Money applyNonProfitDiscount(Money price, boolean nonProfit) {
        return nonProfit ? price.times(NON_PROFIT_FACTOR) : price;
    }

    /**
     * Código comparado sem diferenciar maiúsculas e sem trim (" BLACKFRIDAY " não vale).
     * O antigo teste de código nulo/em branco era redundante: nenhum dos dois casa com um cupom.
     */
    private static Money applyPromoCode(Money price, String promoCode, int monthsActive, LocalDate today) {
        if ("BLACKFRIDAY".equalsIgnoreCase(promoCode) && today.getMonthValue() == 11) {
            return price.minus(BLACK_FRIDAY_DISCOUNT);
        }
        if ("WELCOME10".equalsIgnoreCase(promoCode) && monthsActive <= 1) {
            return price.times(WELCOME_FACTOR);
        }
        return price;
    }

    /** Multa fixa de 2% mais juros simples de 0,1% ao dia, ambos sobre o mesmo preço. */
    private static Money applyOverdueCharges(Money price, int overdueDays) {
        if (overdueDays <= 0) {
            return price;
        }
        Money penalty = price.times(LATE_PENALTY_RATE);
        Money interest = price.times(DAILY_INTEREST_RATE).times(BigDecimal.valueOf(overdueDays));
        return price.plus(penalty).plus(interest);
    }
}
