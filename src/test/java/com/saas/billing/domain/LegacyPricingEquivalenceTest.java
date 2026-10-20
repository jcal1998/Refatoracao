package com.saas.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Teste diferencial: compara o PricingCalculator com a fórmula original em double
 * (linhas 24-57 do legado, copiadas literalmente; só LocalDate.now() virou parâmetro)
 * em milhares de entradas aleatórias com semente fixa.
 *
 * <p>A única diferença tolerada é de um centavo quando o valor exato cai em meio
 * centavo e o double do legado ficava logo abaixo dele (ex.: 831.1949999999999
 * em vez de 831.195). Pode ser apagado quando o time confiar no novo motor.
 */
class LegacyPricingEquivalenceTest {

    private static final String[] PROMO_CODES = {
            null, "", "   ", "BLACKFRIDAY", "blackfriday", " BLACKFRIDAY", "WELCOME10", "welcome10", "PROMO50"};

    @Test
    void matchesTheLegacyFormulaToTheCent() {
        Random random = new Random(20261020L);
        PricingCalculator calculator = new PricingCalculator();
        List<String> divergences = new ArrayList<>();

        for (int i = 0; i < 200_000; i++) {
            BigDecimal basePrice = BigDecimal.valueOf(random.nextInt(100_000), 2);
            LocalDate today = LocalDate.of(2025, 1, 1).plusDays(random.nextInt(365 * 3));
            LocalDate signupDate = today.minusMonths(random.nextInt(64) - 3).withDayOfMonth(1).plusDays(random.nextInt(28));
            int overdueDays = random.nextInt(70) - 5;
            String promoCode = PROMO_CODES[random.nextInt(PROMO_CODES.length)];
            boolean nonProfit = random.nextBoolean();

            double legacy = legacyPrice(basePrice.doubleValue(), signupDate.toString(), overdueDays, promoCode, nonProfit, today);
            BigDecimal refactored = calculator.calculate(new BillingCommand(
                    "C-1", new Money(basePrice), signupDate, overdueDays, promoCode, nonProfit, "tok_x"), today).amount();

            if (BigDecimal.valueOf(legacy).setScale(2, RoundingMode.HALF_UP).compareTo(refactored) != 0
                    && !isHalfCentFloatingPointError(legacy, refactored)) {
                divergences.add(basePrice + " " + signupDate + " " + today + " " + overdueDays + " " + promoCode + " " + nonProfit);
            }
        }

        assertThat(divergences).isEmpty();
    }

    private static boolean isHalfCentFloatingPointError(double legacy, BigDecimal refactored) {
        BigDecimal halfCent = new BigDecimal("0.005");
        BigDecimal value = BigDecimal.valueOf(legacy);
        BigDecimal distance = value.subtract(refactored.subtract(halfCent)).abs()
                .min(value.subtract(refactored.add(halfCent)).abs());
        return distance.compareTo(new BigDecimal("1e-9")) < 0;
    }

    private static double legacyPrice(double basePrice, String signupDateStr, int overdueDays, String promoCode,
                                      boolean isNonProfit, LocalDate today) {
        LocalDate signupDate = LocalDate.parse(signupDateStr);

        int monthsActive = (today.getYear() - signupDate.getYear()) * 12 + (today.getMonthValue() - signupDate.getMonthValue());

        double calculatedPrice = basePrice;

        if (monthsActive > 24) {
            calculatedPrice = calculatedPrice - (basePrice * 0.15); // 15% após 2 anos
        } else if (monthsActive > 12) {
            calculatedPrice = calculatedPrice - (basePrice * 0.10); // 10% após 1 ano
        }

        if (isNonProfit) {
            calculatedPrice = calculatedPrice * 0.70; // 30% off para ONGs
        }

        if (promoCode != null && !promoCode.trim().isEmpty()) {
            if (promoCode.equalsIgnoreCase("BLACKFRIDAY") && today.getMonthValue() == 11) {
                calculatedPrice = calculatedPrice - 30.0;
            } else if (promoCode.equalsIgnoreCase("WELCOME10") && monthsActive <= 1) {
                calculatedPrice = calculatedPrice * 0.90;
            }
        }

        if (overdueDays > 0) {
            double penalty = calculatedPrice * 0.02;
            double dailyInterest = (calculatedPrice * 0.001) * overdueDays;
            calculatedPrice = calculatedPrice + penalty + dailyInterest;
        }

        if (calculatedPrice < 0) {
            calculatedPrice = 0.0;
        }
        return calculatedPrice;
    }
}
