package com.saas.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

/**
 * Testes de caracterização: registram o comportamento ATUAL do código legado,
 * inclusive as esquisitices, para que a refatoração prove que não o alterou.
 */
class SubscriptionBillingServiceTest {

    private static final LocalDate OCTOBER_20 = LocalDate.of(2026, 10, 20);
    private static final LocalDate NOVEMBER_15 = LocalDate.of(2026, 11, 15);
    private static final String VALID_TOKEN = "tok_visa_4242";

    @BeforeAll
    static void installInMemoryDatabase() throws SQLException {
        DriverManager.registerDriver(new PostgresToH2Driver());
        try (Connection connection = h2(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS customers (id VARCHAR(20) PRIMARY KEY, email VARCHAR(100), status VARCHAR(20))");
            statement.execute("CREATE TABLE IF NOT EXISTS invoices (customer_id VARCHAR(20), amount NUMERIC(12, 2), status VARCHAR(10), created_at TIMESTAMP)");
        }
    }

    @BeforeEach
    void seedCustomers() throws SQLException {
        PostgresToH2Driver.failConnections = false;
        try (Connection connection = h2(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM invoices");
            statement.execute("DELETE FROM customers");
            statement.execute("INSERT INTO customers VALUES "
                    + "('C-ACTIVE', 'ana@cliente.com', 'ACTIVE'), "
                    + "('C-LOWER', 'bia@cliente.com', 'active'), "
                    + "('C-SUSPENDED', 'caio@cliente.com', 'SUSPENDED'), "
                    + "('C-NO-STATUS', 'dani@cliente.com', NULL), "
                    + "('C-BAD-EMAIL', 'email-invalido', 'ACTIVE')");
        }
    }

    // ---------------------------------------------------------------- fluxo

    @Test
    void activeCustomerWithValidCardIsChargedInvoicedAndNotified() throws SQLException {
        // Nenhum servidor SMTP existe no teste: o "E-mail disparado" só aparece porque send() está comentado.
        assertThat(scenario().run()).containsExactly(
                "Pagamento processado com sucesso: R$ 100.0",
                "E-mail disparado para: ana@cliente.com");
        assertThat(invoices()).containsExactly("C-ACTIVE 100.00 PAID");
    }

    @Test
    void cardTokenWithoutTokPrefixFailsButInvoiceAndEmailStillHappen() throws SQLException {
        assertThat(scenario().cardToken("card_4242").run()).containsExactly(
                "Falha no pagamento do cartão para: C-ACTIVE",
                "E-mail disparado para: ana@cliente.com");
        assertThat(invoices()).containsExactly("C-ACTIVE 100.00 FAILED");
    }

    @Test
    void missingCardTokenFailsPayment() throws SQLException {
        assertThat(scenario().cardToken(null).run()).first()
                .isEqualTo("Falha no pagamento do cartão para: C-ACTIVE");
        assertThat(invoices()).containsExactly("C-ACTIVE 100.00 FAILED");
    }

    @Test
    void statusIsComparedIgnoringCase() {
        assertThat(scenario().customer("C-LOWER").run()).containsExactly(
                "Pagamento processado com sucesso: R$ 100.0",
                "E-mail disparado para: bia@cliente.com");
    }

    @Test
    void inactiveCustomerIsNotCharged() throws SQLException {
        assertThat(scenario().customer("C-SUSPENDED").run())
                .containsExactly("Cliente inativo. Cobrança cancelada: C-SUSPENDED");
        assertThat(invoices()).isEmpty();
    }

    @Test
    void customerWithoutStatusIsTreatedAsInactive() throws SQLException {
        assertThat(scenario().customer("C-NO-STATUS").run())
                .containsExactly("Cliente inativo. Cobrança cancelada: C-NO-STATUS");
        assertThat(invoices()).isEmpty();
    }

    @Test
    void unknownCustomerIsSilentlyIgnored() throws SQLException {
        assertThat(scenario().customer("C-GHOST").run()).isEmpty();
        assertThat(invoices()).isEmpty();
    }

    @Test
    void invalidSignupDateThrowsBeforeTouchingTheDatabase() throws SQLException {
        assertThatThrownBy(() -> scenario().signup("20/10/2025").run())
                .isInstanceOf(DateTimeParseException.class);
        assertThat(invoices()).isEmpty();
    }

    @Test
    void databaseFailureIsSwallowedIntoTheLogs() {
        PostgresToH2Driver.failConnections = true;

        assertThat(scenario().run())
                .containsExactly("Erro fatal no processamento: Connection to localhost:5432 refused");
    }

    @Test
    void invalidEmailIsSwallowedAfterPaymentAndInvoice() throws SQLException {
        List<String> logs = scenario().customer("C-BAD-EMAIL").run();

        assertThat(logs).hasSize(2);
        assertThat(logs.get(0)).isEqualTo("Pagamento processado com sucesso: R$ 100.0");
        assertThat(logs.get(1)).startsWith("Erro fatal no processamento: ");
        assertThat(invoices()).containsExactly("C-BAD-EMAIL 100.00 PAID");
    }

    @Test
    void planTypeIsIgnored() {
        assertThat(scenario().planType("BASIC").chargedPrice())
                .isEqualTo(scenario().planType("ENTERPRISE").chargedPrice())
                .isEqualTo("R$ 100.0");
    }

    // ---------------------------------------------------------------- preço

    @ParameterizedTest
    @CsvSource({
            "2025-10-20, R$ 100.0", // 12 meses: ainda sem desconto (> 12 é estrito)
            "2025-09-20, R$ 90.0",  // 13 meses: 10%
            "2024-10-20, R$ 90.0",  // 24 meses: ainda 10% (> 24 é estrito)
            "2024-09-20, R$ 85.0"   // 25 meses: 15%
    })
    void loyaltyDiscountUsesStrictBoundaries(String signupDate, String expectedPrice) {
        assertThat(scenario().signup(signupDate).chargedPrice()).isEqualTo(expectedPrice);
    }

    @Test
    void monthsAreCountedByCalendarMonthIgnoringTheDay() {
        // 30/09/2025 -> 20/10/2026 conta 13 meses; ChronoUnit.MONTHS.between daria 12.
        assertThat(scenario().signup("2025-09-30").chargedPrice()).isEqualTo("R$ 90.0");
    }

    @Test
    void nonProfitDiscountIsAppliedAfterLoyalty() {
        assertThat(scenario().signup("2024-09-20").nonProfit().chargedPrice()).isEqualTo("R$ 59.49999999999999");
    }

    @Test
    void floatingPointLeaksIntoTheChargedAmountButNotIntoTheInvoice() throws SQLException {
        assertThat(scenario().basePrice(90.0).nonProfit().chargedPrice()).isEqualTo("R$ 62.99999999999999");
        assertThat(invoices()).containsExactly("C-ACTIVE 63.00 PAID");
    }

    @Test
    void blackFridayOnlyWorksInNovember() {
        assertThat(scenario().today(NOVEMBER_15).promo("BLACKFRIDAY").chargedPrice()).isEqualTo("R$ 70.0");
        assertThat(scenario().today(OCTOBER_20).promo("BLACKFRIDAY").chargedPrice()).isEqualTo("R$ 100.0");
    }

    @Test
    void promoCodeIsCaseInsensitiveButNotTrimmed() {
        assertThat(scenario().today(NOVEMBER_15).promo("blackfriday").chargedPrice()).isEqualTo("R$ 70.0");
        assertThat(scenario().today(NOVEMBER_15).promo(" BLACKFRIDAY ").chargedPrice()).isEqualTo("R$ 100.0");
    }

    @Test
    void blackFridayIsSubtractedAfterNonProfitDiscount() {
        // 100 * 0.70 - 30 = 40 (na ordem inversa seriam 49).
        assertThat(scenario().today(NOVEMBER_15).nonProfit().promo("BLACKFRIDAY").chargedPrice()).isEqualTo("R$ 40.0");
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-01, R$ 90.0",  // 0 meses
            "2026-09-29, R$ 90.0",  // 1 mês
            "2026-08-31, R$ 100.0", // 2 meses
            "2026-12-01, R$ 90.0"   // adesão no futuro: -2 meses também ganha
    })
    void welcomeCouponOnlyForUpToOneMonth(String signupDate, String expectedPrice) {
        assertThat(scenario().signup(signupDate).promo("WELCOME10").chargedPrice()).isEqualTo(expectedPrice);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "PROMO50"})
    void blankOrUnknownPromoCodeIsIgnored(String promoCode) {
        assertThat(scenario().promo(promoCode).chargedPrice()).isEqualTo("R$ 100.0");
    }

    @Test
    void overdueAddsTwoPercentPenaltyPlusSimpleDailyInterest() {
        assertThat(scenario().overdueDays(10).chargedPrice()).isEqualTo("R$ 103.0");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5})
    void noOverdueChargesWithoutPositiveOverdueDays(int overdueDays) {
        assertThat(scenario().overdueDays(overdueDays).chargedPrice()).isEqualTo("R$ 100.0");
    }

    @Test
    void overdueChargesApplyToTheDiscountedPrice() {
        assertThat(scenario().today(NOVEMBER_15).promo("BLACKFRIDAY").overdueDays(10).chargedPrice())
                .isEqualTo("R$ 72.10000000000001");
    }

    @Test
    void negativePriceIsClampedToZeroAfterOverdueCharges() {
        assertThat(scenario().basePrice(20.0).today(NOVEMBER_15).promo("BLACKFRIDAY").chargedPrice())
                .isEqualTo("R$ 0.0");
        assertThat(scenario().basePrice(20.0).today(NOVEMBER_15).promo("BLACKFRIDAY").overdueDays(10).chargedPrice())
                .isEqualTo("R$ 0.0");
    }

    @Test
    void allRulesCombined() throws SQLException {
        // 200 -15% = 170; ONG = 119; Black Friday = 89; multa 2% + 5 dias de juros = 91.225 exatos,
        // mas o double chega a 91.22499999999998 e a fatura perde o centavo do meio (91.22).
        assertThat(scenario().basePrice(200.0).signup("2024-01-10").nonProfit()
                .today(NOVEMBER_15).promo("BLACKFRIDAY").overdueDays(5).chargedPrice())
                .isEqualTo("R$ 91.22499999999998");
        assertThat(invoices()).containsExactly("C-ACTIVE 91.22 PAID");
    }

    // ---------------------------------------------------------------- apoio

    private static Scenario scenario() {
        return new Scenario();
    }

    private static Connection h2() throws SQLException {
        return DriverManager.getConnection(PostgresToH2Driver.H2_URL, "sa", "");
    }

    private static List<String> invoices() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = h2();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT customer_id, amount, status, created_at FROM invoices")) {
            while (rs.next()) {
                assertThat(rs.getTimestamp("created_at")).isNotNull();
                rows.add(rs.getString("customer_id") + " " + rs.getBigDecimal("amount").toPlainString() + " " + rs.getString("status"));
            }
        }
        return rows;
    }

    private static final class Scenario {
        private LocalDate today = OCTOBER_20;
        private String customerId = "C-ACTIVE";
        private String planType = "PRO";
        private double basePrice = 100.0;
        private String signupDate = "2026-10-01";
        private int overdueDays;
        private String promoCode;
        private boolean nonProfit;
        private String cardToken = VALID_TOKEN;

        Scenario today(LocalDate today) {
            this.today = today;
            return this;
        }

        Scenario customer(String customerId) {
            this.customerId = customerId;
            return this;
        }

        Scenario planType(String planType) {
            this.planType = planType;
            return this;
        }

        Scenario basePrice(double basePrice) {
            this.basePrice = basePrice;
            return this;
        }

        Scenario signup(String signupDate) {
            this.signupDate = signupDate;
            return this;
        }

        Scenario overdueDays(int overdueDays) {
            this.overdueDays = overdueDays;
            return this;
        }

        Scenario promo(String promoCode) {
            this.promoCode = promoCode;
            return this;
        }

        Scenario nonProfit() {
            this.nonProfit = true;
            return this;
        }

        Scenario cardToken(String cardToken) {
            this.cardToken = cardToken;
            return this;
        }

        List<String> run() {
            // O legado chama LocalDate.now() estático: só um mock estático consegue congelar a data.
            try (MockedStatic<LocalDate> frozenDate = mockStatic(LocalDate.class, CALLS_REAL_METHODS)) {
                frozenDate.when(LocalDate::now).thenReturn(today);
                return new SubscriptionBillingService().processMonthlyBilling(
                        customerId, planType, basePrice, signupDate, overdueDays, promoCode, nonProfit, cardToken);
            }
        }

        /** O legado só expõe o preço calculado na linha de log do pagamento aprovado. */
        String chargedPrice() {
            String paymentLog = run().get(0);
            assertThat(paymentLog).startsWith("Pagamento processado com sucesso: ");
            return paymentLog.substring("Pagamento processado com sucesso: ".length());
        }
    }
}
