package com.carenexus.api.billing.payment;

import com.carenexus.api.billing.provider.InvalidWebhookSignatureException;
import com.carenexus.api.billing.provider.PaymentGateway;
import com.carenexus.api.billing.repository.PaymentEventRepository;
import com.carenexus.api.billing.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The webhook signature is verified before any database work: a forged callback never opens a transaction (no pooled
 * connection, no payment_events row), and a valid one is processed inside one transaction.
 */
class PaymentWebhookSignatureFirstTest {

    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final PaymentEventRepository events = mock(PaymentEventRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentSettlement settlement = mock(PaymentSettlement.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private final PaymentWebhookService service = new PaymentWebhookService(gateway, events, payments, settlement,
            Clock.systemUTC(), transactionManager);

    @Test
    void aForgedCallback_isRefusedBeforeAnyTransactionOrRepositoryCall() {
        when(gateway.parseWebhook(anyString(), any())).thenThrow(new InvalidWebhookSignatureException());

        assertThrows(InvalidWebhookSignatureException.class,
                () -> service.receive("{\"eventId\":\"evt-1\"}", Map.of("x-carenexus-signature", "sha256=00")));

        verifyNoInteractions(transactionManager);
        verifyNoInteractions(events);
        verifyNoInteractions(payments);
        verifyNoInteractions(settlement);
    }

    @Test
    void aSignedCallback_isProcessedInsideOneTransaction() {
        when(gateway.parseWebhook(anyString(), any())).thenReturn(new PaymentGateway.WebhookEvent(
                "evt-2", PaymentGateway.EVENT_PAYMENT_COMPLETED, "ref-2", "txn-2", null));
        when(gateway.name()).thenReturn("LOCAL");
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        when(events.insertIfAbsent("LOCAL", "evt-2", PaymentGateway.EVENT_PAYMENT_COMPLETED)).thenReturn(0);

        assertEquals(PaymentWebhookService.OUTCOME_DUPLICATE, service.receive("{}", Map.of()));

        verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        verify(transactionManager).commit(any());
    }
}
