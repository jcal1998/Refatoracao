package com.saas.billing.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Dados de entrada de uma cobrança mensal (Introduce Parameter Object).
 *
 * <p>O antigo parâmetro {@code planType} não foi trazido: o legado nunca o lia.
 */
public record BillingCommand(
        String customerId,
        Money basePrice,
        LocalDate signupDate,
        int overdueDays,
        String promoCode,
        boolean nonProfit,
        String paymentCardToken) {

    public BillingCommand {
        Objects.requireNonNull(basePrice, "basePrice");
        Objects.requireNonNull(signupDate, "signupDate");
    }
}
