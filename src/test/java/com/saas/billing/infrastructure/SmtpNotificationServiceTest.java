package com.saas.billing.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saas.billing.domain.Money;
import com.saas.billing.port.BillingInfrastructureException;
import org.junit.jupiter.api.Test;

class SmtpNotificationServiceTest {

    private final SmtpNotificationService withoutDelivery = new SmtpNotificationService(false);

    @Test
    void withDeliveryDisabledBuildsTheEmailButNeverConnects() {
        // Não existe servidor SMTP no teste: se tentasse enviar, falharia.
        assertThatCode(() -> withoutDelivery.sendInvoiceReceipt("ana@cliente.com", Money.of("100.00")))
                .doesNotThrowAnyException();
    }

    @Test
    void invalidAddressFailsExplicitly() {
        assertThatThrownBy(() -> withoutDelivery.sendInvoiceReceipt("email-invalido", Money.of("100.00")))
                .isInstanceOf(BillingInfrastructureException.class)
                .hasMessageContaining("email-invalido");
    }
}
