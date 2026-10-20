package com.saas.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regras de preço testadas direto na classe de domínio, sem portas nem relógio.
 * São os mesmos cenários de caracterização que antes só eram observáveis pelo log.
 */
class PricingCalculatorTest {

    private static final LocalDate OCTOBER_20 = LocalDate.of(2026, 10, 20);
    private static final LocalDate NOVEMBER_15 = LocalDate.of(2026, 11, 15);

    private final PricingCalculator calculator = new PricingCalculator();

    @Test
    void newCustomerPaysTheBasePrice() {
        assertThat(quote().price()).isEqualTo("R$ 100.00");
    }

    @ParameterizedTest
    @CsvSource({
            "2025-10-20, R$ 100.00", // 12 meses: ainda sem desconto (> 12 é estrito)
            "2025-09-20, R$ 90.00",  // 13 meses: 10%
            "2024-10-20, R$ 90.00",  // 24 meses: ainda 10% (> 24 é estrito)
            "2024-09-20, R$ 85.00"   // 25 meses: 15%
    })
    void loyaltyDiscountUsesStrictBoundaries(String signupDate, String expectedPrice) {
        assertThat(quote().signup(signupDate).price()).isEqualTo(expectedPrice);
    }

    @Test
    void monthsAreCountedByCalendarMonthIgnoringTheDay() {
        // 30/09/2025 -> 20/10/2026 conta 13 meses; ChronoUnit.MONTHS.between daria 12.
        assertThat(quote().signup("2025-09-30").price()).isEqualTo("R$ 90.00");
    }

    @Test
    void nonProfitDiscountIsAppliedAfterLoyalty() {
        assertThat(quote().signup("2024-09-20").nonProfit().price()).isEqualTo("R$ 59.50");
    }

    @Test
    void roundsOnlyOnceAtTheEnd() {
        // 10.05 -10% = 9.045; ONG = 6.3315 -> 6.33. Arredondando a cada passo daria 9.05 -> 6.335 -> 6.34.
        assertThat(quote().basePrice("10.05").signup("2025-09-20").nonProfit().price()).isEqualTo("R$ 6.33");
    }

    @Test
    void blackFridayOnlyWorksInNovember() {
        assertThat(quote().on(NOVEMBER_15).promo("BLACKFRIDAY").price()).isEqualTo("R$ 70.00");
        assertThat(quote().on(OCTOBER_20).promo("BLACKFRIDAY").price()).isEqualTo("R$ 100.00");
    }

    @Test
    void promoCodeIsCaseInsensitiveButNotTrimmed() {
        assertThat(quote().on(NOVEMBER_15).promo("blackfriday").price()).isEqualTo("R$ 70.00");
        assertThat(quote().on(NOVEMBER_15).promo(" BLACKFRIDAY ").price()).isEqualTo("R$ 100.00");
    }

    @Test
    void blackFridayIsSubtractedAfterNonProfitDiscount() {
        // 100 * 0.70 - 30 = 40 (na ordem inversa seriam 49).
        assertThat(quote().on(NOVEMBER_15).nonProfit().promo("BLACKFRIDAY").price()).isEqualTo("R$ 40.00");
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-01, R$ 90.00",  // 0 meses
            "2026-09-29, R$ 90.00",  // 1 mês
            "2026-08-31, R$ 100.00", // 2 meses
            "2026-12-01, R$ 90.00"   // adesão no futuro: -2 meses também ganha
    })
    void welcomeCouponOnlyForUpToOneMonth(String signupDate, String expectedPrice) {
        assertThat(quote().signup(signupDate).promo("WELCOME10").price()).isEqualTo(expectedPrice);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "PROMO50"})
    void blankOrUnknownPromoCodeIsIgnored(String promoCode) {
        assertThat(quote().promo(promoCode).price()).isEqualTo("R$ 100.00");
    }

    @Test
    void nullPromoCodeIsIgnored() {
        assertThat(quote().promo(null).price()).isEqualTo("R$ 100.00");
    }

    @Test
    void overdueAddsTwoPercentPenaltyPlusSimpleDailyInterest() {
        assertThat(quote().overdueDays(10).price()).isEqualTo("R$ 103.00");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5})
    void noOverdueChargesWithoutPositiveOverdueDays(int overdueDays) {
        assertThat(quote().overdueDays(overdueDays).price()).isEqualTo("R$ 100.00");
    }

    @Test
    void overdueChargesApplyToTheDiscountedPrice() {
        assertThat(quote().on(NOVEMBER_15).promo("BLACKFRIDAY").overdueDays(10).price()).isEqualTo("R$ 72.10");
    }

    @Test
    void negativePriceIsClampedToZeroAfterOverdueCharges() {
        assertThat(quote().basePrice("20.00").on(NOVEMBER_15).promo("BLACKFRIDAY").price()).isEqualTo("R$ 0.00");
        assertThat(quote().basePrice("20.00").on(NOVEMBER_15).promo("BLACKFRIDAY").overdueDays(10).price())
                .isEqualTo("R$ 0.00");
    }

    @Test
    void allRulesCombined() {
        // 200 -15% = 170; ONG = 119; Black Friday = 89; multa 2% + 5 dias de juros = 91.225 -> 91.23.
        assertThat(quote().basePrice("200.00").signup("2024-01-10").nonProfit()
                .on(NOVEMBER_15).promo("BLACKFRIDAY").overdueDays(5).price())
                .isEqualTo("R$ 91.23");
    }

    private Quote quote() {
        return new Quote();
    }

    private final class Quote {
        private LocalDate today = OCTOBER_20;
        private String basePrice = "100.00";
        private String signupDate = "2026-10-01";
        private int overdueDays;
        private String promoCode;
        private boolean nonProfit;

        Quote on(LocalDate today) {
            this.today = today;
            return this;
        }

        Quote basePrice(String basePrice) {
            this.basePrice = basePrice;
            return this;
        }

        Quote signup(String signupDate) {
            this.signupDate = signupDate;
            return this;
        }

        Quote overdueDays(int overdueDays) {
            this.overdueDays = overdueDays;
            return this;
        }

        Quote promo(String promoCode) {
            this.promoCode = promoCode;
            return this;
        }

        Quote nonProfit() {
            this.nonProfit = true;
            return this;
        }

        String price() {
            BillingCommand command = new BillingCommand(
                    "C-1", Money.of(basePrice), LocalDate.parse(signupDate), overdueDays, promoCode, nonProfit, "tok_x");
            return calculator.calculate(command, today).format();
        }
    }
}
