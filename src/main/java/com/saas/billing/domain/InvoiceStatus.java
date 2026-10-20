package com.saas.billing.domain;

public enum InvoiceStatus {
    PAID,
    FAILED;

    public static InvoiceStatus fromPayment(boolean approved) {
        return approved ? PAID : FAILED;
    }
}
