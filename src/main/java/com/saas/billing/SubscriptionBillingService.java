package com.saas.billing;

import com.saas.billing.domain.BillingCommand;
import com.saas.billing.domain.Customer;
import com.saas.billing.domain.Invoice;
import com.saas.billing.domain.InvoiceStatus;
import com.saas.billing.domain.Money;
import com.saas.billing.domain.PricingCalculator;
import com.saas.billing.port.CustomerRepository;
import com.saas.billing.port.InvoiceRepository;
import com.saas.billing.port.NotificationService;
import com.saas.billing.port.PaymentGateway;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Fachada da cobrança mensal: orquestra preço, cliente, pagamento, fatura e recibo. */
public class SubscriptionBillingService {

    private final PricingCalculator pricingCalculator = new PricingCalculator();
    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentGateway paymentGateway;
    private final NotificationService notificationService;
    private final Clock clock;

    public SubscriptionBillingService(CustomerRepository customerRepository,
                                      InvoiceRepository invoiceRepository,
                                      PaymentGateway paymentGateway,
                                      NotificationService notificationService,
                                      Clock clock) {
        this.customerRepository = customerRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentGateway = paymentGateway;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    public List<String> processMonthlyBilling(BillingCommand command) {
        List<String> logs = new ArrayList<>();
        Money price = pricingCalculator.calculate(command, LocalDate.now(clock));
        Optional<Customer> customer = customerRepository.findById(command.customerId());
        if (customer.isEmpty()) {
            return logs; // como no legado, cliente inexistente é ignorado sem log
        }
        if (!customer.get().isActive()) {
            logs.add("Cliente inativo. Cobrança cancelada: " + command.customerId());
            return logs;
        }

        boolean paid = paymentGateway.charge(command.paymentCardToken(), price);
        logs.add(paid ? "Pagamento processado com sucesso: " + price.format()
                : "Falha no pagamento do cartão para: " + command.customerId());
        invoiceRepository.save(new Invoice(command.customerId(), price, InvoiceStatus.fromPayment(paid)));

        String email = customer.get().email();
        notificationService.sendInvoiceReceipt(email, price);
        logs.add("E-mail disparado para: " + email);
        return logs;
    }
}
