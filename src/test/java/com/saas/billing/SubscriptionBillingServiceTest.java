package com.saas.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saas.billing.domain.BillingCommand;
import com.saas.billing.domain.Customer;
import com.saas.billing.domain.Invoice;
import com.saas.billing.domain.Money;
import com.saas.billing.infrastructure.TokenPrefixPaymentGateway;
import com.saas.billing.port.BillingInfrastructureException;
import com.saas.billing.port.CustomerRepository;
import com.saas.billing.port.NotificationService;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Testes de caracterização: os mesmos cenários escritos contra o legado,
 * agora rodando só em memória (portas falsas + Clock fixo), sem banco nem SMTP.
 */
class SubscriptionBillingServiceTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate OCTOBER_20 = LocalDate.of(2026, 10, 20);
    private static final LocalDate NOVEMBER_15 = LocalDate.of(2026, 11, 15);
    private static final String VALID_TOKEN = "tok_visa_4242";

    private final Map<String, Customer> customers = Stream.of(
                    new Customer("C-ACTIVE", "ana@cliente.com", "ACTIVE"),
                    new Customer("C-LOWER", "bia@cliente.com", "active"),
                    new Customer("C-SUSPENDED", "caio@cliente.com", "SUSPENDED"),
                    new Customer("C-NO-STATUS", "dani@cliente.com", null))
            .collect(Collectors.toMap(Customer::id, Function.identity()));
    private final List<Invoice> savedInvoices = new ArrayList<>();
    private final List<String> sentEmails = new ArrayList<>();

    private CustomerRepository customerRepository = id -> Optional.ofNullable(customers.get(id));
    private NotificationService notificationService = (email, amount) -> sentEmails.add(email + " " + amount.format());

    // ---------------------------------------------------------------- fluxo

    @Test
    void activeCustomerWithValidCardIsChargedInvoicedAndNotified() {
        assertThat(scenario().run()).containsExactly(
                "Pagamento processado com sucesso: R$ 100.00",
                "E-mail disparado para: ana@cliente.com");
        assertThat(invoices()).containsExactly("C-ACTIVE 100.00 PAID");
        assertThat(sentEmails).containsExactly("ana@cliente.com R$ 100.00");
    }

    @Test
    void cardTokenWithoutTokPrefixFailsButInvoiceAndEmailStillHappen() {
        assertThat(scenario().cardToken("card_4242").run()).containsExactly(
                "Falha no pagamento do cartão para: C-ACTIVE",
                "E-mail disparado para: ana@cliente.com");
        assertThat(invoices()).containsExactly("C-ACTIVE 100.00 FAILED");
        assertThat(sentEmails).containsExactly("ana@cliente.com R$ 100.00");
    }

    @Test
    void missingCardTokenFailsPayment() {
        assertThat(scenario().cardToken(null).run()).first()
                .isEqualTo("Falha no pagamento do cartão para: C-ACTIVE");
        assertThat(invoices()).containsExactly("C-ACTIVE 100.00 FAILED");
    }

    @Test
    void statusIsComparedIgnoringCase() {
        assertThat(scenario().customer("C-LOWER").run()).containsExactly(
                "Pagamento processado com sucesso: R$ 100.00",
                "E-mail disparado para: bia@cliente.com");
    }

    @Test
    void inactiveCustomerIsNotCharged() {
        assertThat(scenario().customer("C-SUSPENDED").run())
                .containsExactly("Cliente inativo. Cobrança cancelada: C-SUSPENDED");
        assertThat(invoices()).isEmpty();
        assertThat(sentEmails).isEmpty();
    }

    @Test
    void customerWithoutStatusIsTreatedAsInactive() {
        assertThat(scenario().customer("C-NO-STATUS").run())
                .containsExactly("Cliente inativo. Cobrança cancelada: C-NO-STATUS");
        assertThat(invoices()).isEmpty();
        assertThat(sentEmails).isEmpty();
    }

    @Test
    void unknownCustomerIsSilentlyIgnored() {
        assertThat(scenario().customer("C-GHOST").run()).isEmpty();
        assertThat(invoices()).isEmpty();
        assertThat(sentEmails).isEmpty();
    }

    @Test
    void commandCannotBeBuiltWithoutSignupDateOrBasePrice() {
        // Antes a data chegava como String e falhava no LocalDate.parse; agora o tipo já exige uma data.
        assertThatThrownBy(() -> new BillingCommand("C-ACTIVE", Money.of("100.00"), null, 0, null, false, VALID_TOKEN))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BillingCommand("C-ACTIVE", null, OCTOBER_20, 0, null, false, VALID_TOKEN))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void databaseFailureIsNoLongerSwallowedIntoTheLogs() {
        // Legado: o catch (Exception e) virava "Erro fatal no processamento: ..." e a lista voltava como se nada fosse.
        customerRepository = id -> {
            throw new BillingInfrastructureException("Falha ao consultar o cliente " + id, new SQLException("Connection refused"));
        };

        assertThatThrownBy(() -> scenario().run())
                .isInstanceOf(BillingInfrastructureException.class)
                .hasMessage("Falha ao consultar o cliente C-ACTIVE");
        assertThat(invoices()).isEmpty();
    }

    @Test
    void emailFailurePropagatesAfterPaymentAndInvoice() {
        notificationService = (email, amount) -> {
            throw new BillingInfrastructureException("Falha no e-mail da fatura para " + email, null);
        };

        assertThatThrownBy(() -> scenario().run()).isInstanceOf(BillingInfrastructureException.class);
        assertThat(invoices()).containsExactly("C-ACTIVE 100.00 PAID");
    }

    // ---------------------------------------------------------------- preço

    @ParameterizedTest
    @CsvSource({
            "2025-10-20, R$ 100.00", // 12 meses: ainda sem desconto (> 12 é estrito)
            "2025-09-20, R$ 90.00",  // 13 meses: 10%
            "2024-10-20, R$ 90.00",  // 24 meses: ainda 10% (> 24 é estrito)
            "2024-09-20, R$ 85.00"   // 25 meses: 15%
    })
    void loyaltyDiscountUsesStrictBoundaries(String signupDate, String expectedPrice) {
        assertThat(scenario().signup(signupDate).chargedPrice()).isEqualTo(expectedPrice);
    }

    @Test
    void monthsAreCountedByCalendarMonthIgnoringTheDay() {
        // 30/09/2025 -> 20/10/2026 conta 13 meses; ChronoUnit.MONTHS.between daria 12.
        assertThat(scenario().signup("2025-09-30").chargedPrice()).isEqualTo("R$ 90.00");
    }

    @Test
    void nonProfitDiscountIsAppliedAfterLoyalty() {
        assertThat(scenario().signup("2024-09-20").nonProfit().chargedPrice()).isEqualTo("R$ 59.50");
    }

    @Test
    void chargedAmountAndInvoiceAgreeToTheCent() {
        // No legado o log/e-mail mostrava R$ 62.99999999999999 enquanto a fatura gravava 63.00.
        assertThat(scenario().basePrice("90.00").nonProfit().chargedPrice()).isEqualTo("R$ 63.00");
        assertThat(invoices()).containsExactly("C-ACTIVE 63.00 PAID");
    }

    @Test
    void blackFridayOnlyWorksInNovember() {
        assertThat(scenario().today(NOVEMBER_15).promo("BLACKFRIDAY").chargedPrice()).isEqualTo("R$ 70.00");
        assertThat(scenario().today(OCTOBER_20).promo("BLACKFRIDAY").chargedPrice()).isEqualTo("R$ 100.00");
    }

    @Test
    void todayComesFromTheInjectedClockInItsOwnZone() {
        // 01/11 01:00 UTC ainda é 31/10 em São Paulo: nada de Black Friday.
        Clock lateOctoberNight = Clock.fixed(Instant.parse("2026-11-01T01:00:00Z"), SAO_PAULO);

        assertThat(scenario().clock(lateOctoberNight).promo("BLACKFRIDAY").chargedPrice()).isEqualTo("R$ 100.00");
    }

    @Test
    void promoCodeIsCaseInsensitiveButNotTrimmed() {
        assertThat(scenario().today(NOVEMBER_15).promo("blackfriday").chargedPrice()).isEqualTo("R$ 70.00");
        assertThat(scenario().today(NOVEMBER_15).promo(" BLACKFRIDAY ").chargedPrice()).isEqualTo("R$ 100.00");
    }

    @Test
    void blackFridayIsSubtractedAfterNonProfitDiscount() {
        // 100 * 0.70 - 30 = 40 (na ordem inversa seriam 49).
        assertThat(scenario().today(NOVEMBER_15).nonProfit().promo("BLACKFRIDAY").chargedPrice()).isEqualTo("R$ 40.00");
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-01, R$ 90.00",  // 0 meses
            "2026-09-29, R$ 90.00",  // 1 mês
            "2026-08-31, R$ 100.00", // 2 meses
            "2026-12-01, R$ 90.00"   // adesão no futuro: -2 meses também ganha
    })
    void welcomeCouponOnlyForUpToOneMonth(String signupDate, String expectedPrice) {
        assertThat(scenario().signup(signupDate).promo("WELCOME10").chargedPrice()).isEqualTo(expectedPrice);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "PROMO50"})
    void blankOrUnknownPromoCodeIsIgnored(String promoCode) {
        assertThat(scenario().promo(promoCode).chargedPrice()).isEqualTo("R$ 100.00");
    }

    @Test
    void overdueAddsTwoPercentPenaltyPlusSimpleDailyInterest() {
        assertThat(scenario().overdueDays(10).chargedPrice()).isEqualTo("R$ 103.00");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5})
    void noOverdueChargesWithoutPositiveOverdueDays(int overdueDays) {
        assertThat(scenario().overdueDays(overdueDays).chargedPrice()).isEqualTo("R$ 100.00");
    }

    @Test
    void overdueChargesApplyToTheDiscountedPrice() {
        assertThat(scenario().today(NOVEMBER_15).promo("BLACKFRIDAY").overdueDays(10).chargedPrice())
                .isEqualTo("R$ 72.10");
    }

    @Test
    void negativePriceIsClampedToZeroAfterOverdueCharges() {
        assertThat(scenario().basePrice("20.00").today(NOVEMBER_15).promo("BLACKFRIDAY").chargedPrice())
                .isEqualTo("R$ 0.00");
        assertThat(scenario().basePrice("20.00").today(NOVEMBER_15).promo("BLACKFRIDAY").overdueDays(10).chargedPrice())
                .isEqualTo("R$ 0.00");
    }

    @Test
    void allRulesCombined() {
        // 200 -15% = 170; ONG = 119; Black Friday = 89; multa 2% + 5 dias de juros = 91.225 exatos.
        // O legado (double) chegava a 91.22499999999998 e gravava 91.22; com Money o meio centavo
        // arredonda para cima uma única vez: 91.23. Única diferença de valor, intencional.
        assertThat(scenario().basePrice("200.00").signup("2024-01-10").nonProfit()
                .today(NOVEMBER_15).promo("BLACKFRIDAY").overdueDays(5).chargedPrice())
                .isEqualTo("R$ 91.23");
        assertThat(invoices()).containsExactly("C-ACTIVE 91.23 PAID");
    }

    // ---------------------------------------------------------------- apoio

    private Scenario scenario() {
        return new Scenario();
    }

    private List<String> invoices() {
        return savedInvoices.stream()
                .map(invoice -> invoice.customerId() + " " + invoice.amount().amount().toPlainString() + " " + invoice.status())
                .toList();
    }

    private static Clock fixedAt(LocalDate date) {
        return Clock.fixed(date.atStartOfDay(SAO_PAULO).toInstant(), SAO_PAULO);
    }

    private final class Scenario {
        private Clock clock = fixedAt(OCTOBER_20);
        private String customerId = "C-ACTIVE";
        private String basePrice = "100.00";
        private String signupDate = "2026-10-01";
        private int overdueDays;
        private String promoCode;
        private boolean nonProfit;
        private String cardToken = VALID_TOKEN;

        Scenario today(LocalDate today) {
            this.clock = fixedAt(today);
            return this;
        }

        Scenario clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        Scenario customer(String customerId) {
            this.customerId = customerId;
            return this;
        }

        Scenario basePrice(String basePrice) {
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
            SubscriptionBillingService service = new SubscriptionBillingService(
                    customerRepository, savedInvoices::add, new TokenPrefixPaymentGateway(), notificationService, clock);
            return service.processMonthlyBilling(new BillingCommand(
                    customerId, Money.of(basePrice), LocalDate.parse(signupDate), overdueDays, promoCode, nonProfit, cardToken));
        }

        /** O preço continua observado pela linha de log do pagamento aprovado, como no legado. */
        String chargedPrice() {
            String paymentLog = run().get(0);
            assertThat(paymentLog).startsWith("Pagamento processado com sucesso: ");
            return paymentLog.substring("Pagamento processado com sucesso: ".length());
        }
    }
}
