package com.saas.billing.domain;

public record Customer(String id, String email, String status) {

    /** Status comparado sem diferenciar maiúsculas; status ausente conta como inativo. */
    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}
