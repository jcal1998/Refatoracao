package com.saas.billing.infrastructure;

import com.saas.billing.domain.Money;
import com.saas.billing.port.BillingInfrastructureException;
import com.saas.billing.port.NotificationService;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;

public final class SmtpNotificationService implements NotificationService {

    private static final String HOST = "smtp.empresa.com";
    private static final int PORT = 587;
    private static final String FROM = "billing@empresa.com";

    private final boolean deliveryEnabled;

    /**
     * No legado o {@code send()} estava comentado: o e-mail era montado (e o
     * endereço validado), mas nunca enviado. Com {@code deliveryEnabled = false}
     * esse comportamento é preservado; ligar o envio é uma decisão de negócio,
     * agora explícita na configuração em vez de escondida num comentário.
     */
    public SmtpNotificationService(boolean deliveryEnabled) {
        this.deliveryEnabled = deliveryEnabled;
    }

    @Override
    public void sendInvoiceReceipt(String email, Money amount) {
        try {
            SimpleEmail message = new SimpleEmail();
            message.setHostName(HOST);
            message.setSmtpPort(PORT);
            message.setFrom(FROM);
            message.setSubject("Sua Fatura Mensal");
            message.setMsg("Olá! Sua fatura foi processada no valor de " + amount.format());
            message.addTo(email);
            if (deliveryEnabled) {
                message.send();
            }
        } catch (EmailException e) {
            throw new BillingInfrastructureException("Falha no e-mail da fatura para " + email, e);
        }
    }
}
