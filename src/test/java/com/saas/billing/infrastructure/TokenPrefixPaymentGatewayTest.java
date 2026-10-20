package com.saas.billing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.saas.billing.domain.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TokenPrefixPaymentGatewayTest {

    private final TokenPrefixPaymentGateway gateway = new TokenPrefixPaymentGateway();

    @Test
    void approvesTokensStartingWithTok() {
        assertThat(gateway.charge("tok_visa_4242", Money.of("100.00"))).isTrue();
    }

    @Test
    void approvesEvenAZeroAmount() {
        assertThat(gateway.charge("tok_visa_4242", Money.ZERO)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"card_4242", "TOK_visa", " tok_visa"})
    void declinesEverythingElse(String cardToken) {
        // startsWith diferencia maiúsculas e não ignora espaços, como no legado.
        assertThat(gateway.charge(cardToken, Money.of("100.00"))).isFalse();
    }
}
