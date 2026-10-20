package com.saas.billing.port;

import com.saas.billing.domain.Invoice;

public interface InvoiceRepository {

    void save(Invoice invoice);
}
