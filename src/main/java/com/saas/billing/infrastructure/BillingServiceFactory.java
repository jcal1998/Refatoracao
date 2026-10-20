package com.saas.billing.infrastructure;

import com.saas.billing.SubscriptionBillingService;
import java.time.Clock;
import java.util.Map;

/** Raiz de composição: o único lugar que conhece as implementações concretas. */
public final class BillingServiceFactory {

    private BillingServiceFactory() {
    }

    public static SubscriptionBillingService create(Map<String, String> env) {
        JdbcConnectionFactory database = JdbcConnectionFactory.fromEnvironment(env);
        boolean emailDelivery = Boolean.parseBoolean(env.get("BILLING_EMAIL_DELIVERY_ENABLED"));
        return new SubscriptionBillingService(
                new JdbcCustomerRepository(database),
                new JdbcInvoiceRepository(database),
                new TokenPrefixPaymentGateway(),
                new SmtpNotificationService(emailDelivery),
                // Mesmo fuso de LocalDate.now() no legado; systemUTC() mudaria a data entre 21h e 0h em Brasília.
                Clock.systemDefaultZone());
    }
}
