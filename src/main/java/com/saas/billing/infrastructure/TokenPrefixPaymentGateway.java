package com.saas.billing.infrastructure;

import com.saas.billing.domain.Money;
import com.saas.billing.port.PaymentGateway;

/**
 * O legado nunca chamou um gateway de verdade: aprovava qualquer token que
 * começasse com {@code tok_} (diferenciando maiúsculas). Este adaptador mantém
 * exatamente essa regra; trocar por uma integração real é outra implementação
 * da mesma porta, sem tocar no serviço.
 */
public final class TokenPrefixPaymentGateway implements PaymentGateway {

    @Override
    public boolean charge(String cardToken, Money amount) {
        return cardToken != null && cardToken.startsWith("tok_");
    }
}
