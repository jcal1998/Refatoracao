package com.saas.billing.port;

/**
 * Falha de infraestrutura (banco, e-mail...) por trás de uma porta. Substitui o
 * {@code catch (Exception e)} do legado, que transformava essas falhas numa linha
 * de log e devolvia a lista como se a cobrança tivesse terminado.
 */
public class BillingInfrastructureException extends RuntimeException {

    public BillingInfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
