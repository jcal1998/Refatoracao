package com.saas.billing.domain;

public record Invoice(String customerId, Money amount, InvoiceStatus status) {
}
