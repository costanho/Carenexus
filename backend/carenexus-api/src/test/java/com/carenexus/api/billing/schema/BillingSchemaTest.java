package com.carenexus.api.billing.schema;

import com.carenexus.api.BaseIntegrationTest;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.billing.config.BillingConfig;
import com.carenexus.api.billing.config.BillingProperties;
import com.carenexus.api.billing.model.FeeSchedule;
import com.carenexus.api.billing.model.InsuranceClaim;
import com.carenexus.api.billing.model.Invoice;
import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.model.PaymentEvent;
import com.carenexus.api.billing.model.PaymentMethod;
import com.carenexus.api.billing.repository.FeeScheduleRepository;
import com.carenexus.api.billing.repository.InsuranceClaimRepository;
import com.carenexus.api.billing.repository.InvoiceRepository;
import com.carenexus.api.billing.repository.InvoiceSpecifications;
import com.carenexus.api.billing.repository.PaymentEventRepository;
import com.carenexus.api.billing.repository.PaymentMethodRepository;
import com.carenexus.api.billing.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The billing foundation (V49, the six billing entities and repositories, BillingProperties):
 * the context starts with {@code ddl-auto=validate}, every entity round-trips through the real schema, and the
 * database refuses what the billing rules forbid (a second default method, a second open claim, a second
 * non-cancelled invoice for a visit, amounts that are not positive).
 * <p>
 * Statements that must be refused run through JDBC inside a savepoint, so the test transaction stays usable.
 * How V49 treats rows that existed before it is covered by {@link BillingMigrationTest}.
 */
public class BillingSchemaTest extends BaseIntegrationTest {

    private static final String LINE_ITEMS =
            "[{\"description\": \"Video consultation\", \"quantity\": 1, \"unitPrice\": 1500.00, \"amount\": 1500.00}]";

    private static final Pageable NEWEST_FIRST =
            PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("invoiceId")));

    @Autowired
    private InvoiceRepository invoiceRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentMethodRepository paymentMethodRepository;
    @Autowired
    private InsuranceClaimRepository insuranceClaimRepository;
    @Autowired
    private PaymentEventRepository paymentEventRepository;
    @Autowired
    private FeeScheduleRepository feeScheduleRepository;
    @Autowired
    private BillingProperties billingProperties;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @PersistenceContext
    private EntityManager entityManager;

    @Value("${spring.jpa.hibernate.ddl-auto}")
    private String ddlAuto;

    private User patient;
    private User otherPatient;
    private User doctor;
    private User otherDoctor;
    private User admin;

    @BeforeEach
    void createUsers() {
        patient = createAndSaveUser(49001, "PAT-49001", "billing.schema.patient@example.com", "PATIENT");
        otherPatient = createAndSaveUser(49002, "PAT-49002", "billing.schema.other.patient@example.com", "PATIENT");
        doctor = createAndSaveUser(49003, "DOC-49001", "billing.schema.doctor@example.com", "DOCTOR");
        otherDoctor = createAndSaveUser(49004, "DOC-49002", "billing.schema.other.doctor@example.com", "DOCTOR");
        admin = createAndSaveUser(49005, "ADM-49001", "billing.schema.admin@example.com", "ADMIN");
    }

    // ==================== CONTEXT AND MAPPING ====================

    @Test
    void contextStarts_withSchemaValidation_andEveryBillingEntityIsMapped() {
        // The context of this test class only exists because Hibernate accepted the six mappings against V49.
        assertEquals("validate", ddlAuto);
        for (Class<?> entity : List.of(Invoice.class, Payment.class, PaymentMethod.class, InsuranceClaim.class,
                PaymentEvent.class, FeeSchedule.class)) {
            assertNotNull(entityManager.getMetamodel().entity(entity), entity.getSimpleName());
        }
        // The legacy integer keys are still in the tables (nothing was dropped) and are not mapped.
        assertEquals(4L, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND is_nullable = 'YES' AND ("
                + "(table_name IN ('invoices', 'payments', 'insurance_claims') AND column_name = 'patient_id') "
                + "OR (table_name = 'payment_methods' AND column_name = 'user_id'))", Long.class));
        assertThrows(IllegalArgumentException.class,
                () -> entityManager.getMetamodel().entity(Invoice.class).getAttribute("patientId"));
    }

    // ==================== ROUND TRIPS ====================

    @Test
    void invoice_roundTrips_withEveryColumn() throws Exception {
        Integer appointmentId = appointment(patient, doctor);
        Integer consultationId = consultation(appointmentId, patient, doctor);
        LocalDateTime created = LocalDateTime.now().minusDays(3).truncatedTo(ChronoUnit.SECONDS);

        Invoice saved = invoiceRepository.saveAndFlush(Invoice.builder()
                .patientSystemId(patient.getSystemId())
                .doctorSystemId(doctor.getSystemId())
                .createdBySystemId(admin.getSystemId())
                .appointmentId(appointmentId)
                .consultationId(consultationId)
                .invoiceNo("INV-2026-490001")
                .lineItems(LINE_ITEMS)
                .subtotal(money("1500.00"))
                .tax(money("240.00"))
                .discount(money("100.00"))
                .total(money("1640.00"))
                .amountPaid(money("640.00"))
                .currency("USD")
                .status(Invoice.STATUS_PARTIALLY_PAID)
                .dueDate(LocalDate.of(2026, 11, 24))
                .issuedAt(created.plusHours(1))
                .paidAt(created.plusHours(2))
                .cancelReason("Entered twice")
                .notes("Thank you")
                .createdAt(created)
                .build());
        entityManager.clear();

        Invoice read = invoiceRepository.findById(saved.getInvoiceId()).orElseThrow();
        assertEquals(patient.getSystemId(), read.getPatientSystemId());
        assertEquals(doctor.getSystemId(), read.getDoctorSystemId());
        assertEquals(admin.getSystemId(), read.getCreatedBySystemId());
        assertEquals(appointmentId, read.getAppointmentId());
        assertEquals(consultationId, read.getConsultationId());
        assertEquals("INV-2026-490001", read.getInvoiceNo());
        assertEquals(objectMapper.readTree(LINE_ITEMS), objectMapper.readTree(read.getLineItems()));
        assertMoney("1500.00", read.getSubtotal());
        assertMoney("240.00", read.getTax());
        assertMoney("100.00", read.getDiscount());
        assertMoney("1640.00", read.getTotal());
        assertMoney("640.00", read.getAmountPaid());
        assertEquals("USD", read.getCurrency());
        assertEquals(Invoice.STATUS_PARTIALLY_PAID, read.getStatus());
        assertEquals(LocalDate.of(2026, 11, 24), read.getDueDate());
        assertEquals(created.plusHours(1), read.getIssuedAt());
        assertEquals(created.plusHours(2), read.getPaidAt());
        assertEquals("Entered twice", read.getCancelReason());
        assertEquals("Thank you", read.getNotes());
        assertEquals(created, read.getCreatedAt());
        assertEquals(created, read.getUpdatedAt());
        assertEquals(read.getInvoiceId(), invoiceRepository.findByInvoiceNo("INV-2026-490001").orElseThrow().getInvoiceId());
    }

    @Test
    void invoice_defaults_areDraftKesAndZeroAmounts() {
        Invoice saved = invoiceRepository.saveAndFlush(Invoice.builder()
                .patientSystemId(patient.getSystemId()).invoiceNo("INV-2026-490002").build());
        entityManager.clear();

        Invoice read = invoiceRepository.findById(saved.getInvoiceId()).orElseThrow();
        assertEquals(Invoice.STATUS_DRAFT, read.getStatus());
        assertEquals("KES", read.getCurrency());
        for (BigDecimal amount : List.of(read.getSubtotal(), read.getTax(), read.getDiscount(), read.getTotal(),
                read.getAmountPaid())) {
            assertMoney("0.00", amount);
        }
        assertNull(read.getDoctorSystemId());
        assertNull(read.getIssuedAt());
        assertNull(read.getLineItems());
        assertNotNull(read.getCreatedAt());
        assertNotNull(read.getUpdatedAt());
        // A new no-argument instance carries the same defaults as the builder.
        Invoice blank = new Invoice();
        assertEquals(Invoice.STATUS_DRAFT, blank.getStatus());
        assertMoney("0.00", blank.getAmountPaid());
    }

    @Test
    void paymentMethod_roundTrips_withEveryColumn() {
        LocalDateTime created = LocalDateTime.now().minusDays(1).truncatedTo(ChronoUnit.SECONDS);
        PaymentMethod saved = paymentMethodRepository.saveAndFlush(PaymentMethod.builder()
                .systemId(patient.getSystemId())
                .type(PaymentMethod.TYPE_INSURANCE)
                .provider("Jubilee Health")
                .lastFour("13-K")
                .mpesaPhone("+254 712 344 567")
                .insurerName("Jubilee Health")
                .policyNumber("JH-88213-K")
                .memberNumber("0045712")
                .displayLabel("Jubilee Health \u2022\u2022\u2022\u2022 13-K")
                .providerToken("local_pm_0123456789abcdef0123456789abcdef")
                .isDefault(true)
                .isActive(true)
                .isDeleted(false)
                .createdAt(created)
                .build());
        entityManager.clear();

        PaymentMethod read = paymentMethodRepository.findById(saved.getMethodId()).orElseThrow();
        assertEquals(patient.getSystemId(), read.getSystemId());
        assertEquals(PaymentMethod.TYPE_INSURANCE, read.getType());
        assertEquals("Jubilee Health", read.getProvider());
        assertEquals("13-K", read.getLastFour());
        assertEquals("+254 712 344 567", read.getMpesaPhone());
        assertEquals("Jubilee Health", read.getInsurerName());
        assertEquals("JH-88213-K", read.getPolicyNumber());
        assertEquals("0045712", read.getMemberNumber());
        assertEquals("Jubilee Health \u2022\u2022\u2022\u2022 13-K", read.getDisplayLabel());
        assertEquals("local_pm_0123456789abcdef0123456789abcdef", read.getProviderToken());
        assertTrue(read.getIsDefault());
        assertTrue(read.getIsActive());
        assertFalse(read.getIsDeleted());
        assertEquals(created, read.getCreatedAt());
        // The legacy owner key is no longer needed for a new row.
        assertNull(jdbc.queryForObject("SELECT user_id FROM payment_methods WHERE method_id = ?", Integer.class,
                read.getMethodId()));
    }

    @Test
    void payment_roundTrips_withEveryColumn() throws Exception {
        Invoice invoice = savedInvoice("INV-2026-490003", Invoice.STATUS_SENT, null, null);
        PaymentMethod method = paymentMethodRepository.saveAndFlush(PaymentMethod.builder()
                .systemId(patient.getSystemId()).type(PaymentMethod.TYPE_MPESA).mpesaPhone("+254712344567").build());
        Integer appointmentId = appointment(patient, doctor);
        LocalDateTime created = LocalDateTime.now().minusHours(5).truncatedTo(ChronoUnit.SECONDS);
        String gatewayResponse = "{\"result\": \"accepted\", \"attempt\": 1}";

        Payment saved = paymentRepository.saveAndFlush(Payment.builder()
                .patientSystemId(patient.getSystemId())
                .appointmentId(appointmentId)
                .invoiceId(invoice.getInvoiceId())
                .paymentMethodId(method.getMethodId())
                .amount(money("900.00"))
                .currency("KES")
                .status(Payment.STATUS_REFUNDED)
                .method(PaymentMethod.TYPE_MPESA)
                .transactionId("LOC-8K2M4QX7P1ZD")
                .gateway("local")
                .gatewayResponse(gatewayResponse)
                .providerReference("local_pay_5f0c2a7e9b1d4c6fa3e8d2b47c915a60")
                .failureReason("Payment was declined")
                .initiatedBySystemId(admin.getSystemId())
                .refundedAmount(money("900.00"))
                .refundReason("Charged twice")
                .payerPhone("+254712344567")
                .paidAt(created.plusMinutes(2))
                .createdAt(created)
                .build());
        entityManager.clear();

        Payment read = paymentRepository.findById(saved.getPaymentId()).orElseThrow();
        assertEquals(patient.getSystemId(), read.getPatientSystemId());
        assertEquals(appointmentId, read.getAppointmentId());
        assertEquals(invoice.getInvoiceId(), read.getInvoiceId());
        assertEquals(method.getMethodId(), read.getPaymentMethodId());
        assertMoney("900.00", read.getAmount());
        assertEquals("KES", read.getCurrency());
        assertEquals(Payment.STATUS_REFUNDED, read.getStatus());
        assertEquals(PaymentMethod.TYPE_MPESA, read.getMethod());
        assertEquals("LOC-8K2M4QX7P1ZD", read.getTransactionId());
        assertEquals("local", read.getGateway());
        assertEquals(objectMapper.readTree(gatewayResponse), objectMapper.readTree(read.getGatewayResponse()));
        assertEquals("local_pay_5f0c2a7e9b1d4c6fa3e8d2b47c915a60", read.getProviderReference());
        assertEquals("Payment was declined", read.getFailureReason());
        assertEquals(admin.getSystemId(), read.getInitiatedBySystemId());
        assertMoney("900.00", read.getRefundedAmount());
        assertEquals("Charged twice", read.getRefundReason());
        assertEquals("+254712344567", read.getPayerPhone());
        assertEquals(created.plusMinutes(2), read.getPaidAt());
        assertEquals(created, read.getCreatedAt());
        assertEquals(created, read.getUpdatedAt());

        // Defaults of a new payment: PENDING, KES, nothing refunded.
        Payment pending = paymentRepository.saveAndFlush(Payment.builder().patientSystemId(patient.getSystemId())
                .invoiceId(invoice.getInvoiceId()).amount(money("10.00")).method(PaymentMethod.TYPE_CASH).build());
        entityManager.clear();
        Payment readPending = paymentRepository.findById(pending.getPaymentId()).orElseThrow();
        assertEquals(Payment.STATUS_PENDING, readPending.getStatus());
        assertEquals("KES", readPending.getCurrency());
        assertMoney("0.00", readPending.getRefundedAmount());
        assertNull(readPending.getGatewayResponse());
    }

    @Test
    void insuranceClaim_roundTrips_withEveryColumn() throws Exception {
        Invoice invoice = savedInvoice("INV-2026-490004", Invoice.STATUS_SENT, null, null);
        Payment settlement = paymentRepository.saveAndFlush(Payment.builder().patientSystemId(patient.getSystemId())
                .invoiceId(invoice.getInvoiceId()).amount(money("1000.00")).method(PaymentMethod.TYPE_INSURANCE)
                .status(Payment.STATUS_COMPLETED).gateway("INSURANCE").build());
        LocalDateTime submitted = LocalDateTime.now().minusDays(2).truncatedTo(ChronoUnit.SECONDS);
        String longReason = "x".repeat(1000);

        InsuranceClaim saved = insuranceClaimRepository.saveAndFlush(InsuranceClaim.builder()
                .patientSystemId(patient.getSystemId())
                .paymentId(settlement.getPaymentId())
                .invoiceId(invoice.getInvoiceId())
                .claimNo("CLM-2026-490001")
                .insuranceProvider("Jubilee Health")
                .policyNumber("JH-88213-K")
                .memberNumber("0045712")
                .claimAmount(money("1500.00"))
                .approvedAmount(money("1000.00"))
                .rejectionReason(longReason)
                .appealReason(longReason)
                .providerReference("MANUAL-CLM-2026-490001")
                .documentIds("[12, 15]")
                .status(InsuranceClaim.STATUS_PARTIAL)
                .submittedBySystemId(patient.getSystemId())
                .reviewedBySystemId(admin.getSystemId())
                .submittedAt(submitted)
                .resolvedAt(submitted.plusDays(1))
                .build());
        entityManager.clear();

        InsuranceClaim read = insuranceClaimRepository.findById(saved.getClaimId()).orElseThrow();
        assertEquals(patient.getSystemId(), read.getPatientSystemId());
        assertEquals(settlement.getPaymentId(), read.getPaymentId());
        assertEquals(invoice.getInvoiceId(), read.getInvoiceId());
        assertEquals("CLM-2026-490001", read.getClaimNo());
        assertEquals("Jubilee Health", read.getInsuranceProvider());
        assertEquals("JH-88213-K", read.getPolicyNumber());
        assertEquals("0045712", read.getMemberNumber());
        assertMoney("1500.00", read.getClaimAmount());
        assertMoney("1000.00", read.getApprovedAmount());
        assertEquals(longReason, read.getRejectionReason());
        assertEquals(longReason, read.getAppealReason());
        assertEquals("MANUAL-CLM-2026-490001", read.getProviderReference());
        assertEquals(objectMapper.readTree("[12, 15]"), objectMapper.readTree(read.getDocumentIds()));
        assertEquals(InsuranceClaim.STATUS_PARTIAL, read.getStatus());
        assertEquals(patient.getSystemId(), read.getSubmittedBySystemId());
        assertEquals(admin.getSystemId(), read.getReviewedBySystemId());
        assertEquals(submitted, read.getSubmittedAt());
        assertEquals(submitted.plusDays(1), read.getResolvedAt());
        assertEquals(submitted, read.getUpdatedAt());
        assertEquals(read.getClaimId(), insuranceClaimRepository.findByClaimNo("CLM-2026-490001").orElseThrow().getClaimId());

        // Defaults of a new claim: SUBMITTED, no documents, no claim number yet.
        InsuranceClaim minimal = insuranceClaimRepository.saveAndFlush(InsuranceClaim.builder()
                .patientSystemId(otherPatient.getSystemId()).insuranceProvider("AAR").policyNumber("P-1")
                .claimAmount(money("1.00")).build());
        entityManager.clear();
        InsuranceClaim readMinimal = insuranceClaimRepository.findById(minimal.getClaimId()).orElseThrow();
        assertEquals(InsuranceClaim.STATUS_SUBMITTED, readMinimal.getStatus());
        assertEquals(objectMapper.readTree("[]"), objectMapper.readTree(readMinimal.getDocumentIds()));
        assertNull(readMinimal.getClaimNo());
        assertNull(readMinimal.getApprovedAmount());
        assertNotNull(readMinimal.getSubmittedAt());
    }

    @Test
    void paymentEvent_isInsertedOnce_andRoundTrips() {
        Invoice invoice = savedInvoice("INV-2026-490005", Invoice.STATUS_SENT, null, null);
        Payment payment = paymentRepository.saveAndFlush(Payment.builder().patientSystemId(patient.getSystemId())
                .invoiceId(invoice.getInvoiceId()).amount(money("1500.00")).method(PaymentMethod.TYPE_CARD).build());

        assertEquals(1, paymentEventRepository.insertIfAbsent("local", "evt_0b7d3c1e52a94f08", "PAYMENT_COMPLETED"));
        // A duplicate delivery is not an error and leaves the transaction usable.
        assertEquals(0, paymentEventRepository.insertIfAbsent("local", "evt_0b7d3c1e52a94f08", "PAYMENT_FAILED"));
        // The same event id of another gateway is a different event.
        assertEquals(1, paymentEventRepository.insertIfAbsent("other", "evt_0b7d3c1e52a94f08", "PAYMENT_COMPLETED"));

        PaymentEvent event = paymentEventRepository.findByProviderAndEventId("local", "evt_0b7d3c1e52a94f08").orElseThrow();
        assertEquals("PAYMENT_COMPLETED", event.getEventType());
        assertNotNull(event.getReceivedAt());
        assertNull(event.getOutcome());
        assertNull(event.getPaymentId());
        assertNull(event.getProcessedAt());

        Instant processed = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        event.setPaymentId(payment.getPaymentId());
        event.setOutcome(PaymentEvent.OUTCOME_APPLIED);
        event.setProcessedAt(processed);
        paymentEventRepository.saveAndFlush(event);
        entityManager.clear();

        PaymentEvent read = paymentEventRepository.findById(event.getPaymentEventId()).orElseThrow();
        assertEquals("local", read.getProvider());
        assertEquals("evt_0b7d3c1e52a94f08", read.getEventId());
        assertEquals(payment.getPaymentId(), read.getPaymentId());
        assertEquals(PaymentEvent.OUTCOME_APPLIED, read.getOutcome());
        assertEquals(processed, read.getProcessedAt());

        // An entity saved directly gets its receive time; an unknown outcome is refused.
        PaymentEvent direct = paymentEventRepository.saveAndFlush(PaymentEvent.builder().provider("local")
                .eventId("evt_direct").eventType("OTHER").outcome(PaymentEvent.OUTCOME_STALE).build());
        assertNotNull(direct.getReceivedAt());
        assertRejected("payment_events_outcome_check", () -> jdbc.update(
                "INSERT INTO payment_events (provider, event_id, event_type, outcome) VALUES ('local', 'evt_bad', 'OTHER', 'REMOVAL_ENQUEUED')"));
        assertRejected("ux_payment_events_event", () -> jdbc.update(
                "INSERT INTO payment_events (provider, event_id, event_type) VALUES ('local', 'evt_direct', 'OTHER')"));
    }

    @Test
    void feeSchedule_shipsTheClinicDefaults_andRoundTripsADoctorOverride() {
        List<FeeSchedule> defaults = feeScheduleRepository.findByDoctorSystemIdIsNull();
        assertEquals(3, defaults.size());
        Map<String, String> expected = Map.of("VIDEO", "1500.00", "IN_PERSON", "2000.00", "PHONE", "1000.00");
        for (FeeSchedule row : defaults) {
            assertMoney(expected.get(row.getAppointmentType()), row.getAmount());
            assertEquals("KES", row.getCurrency());
            assertTrue(row.getIsActive());
            assertNull(row.getDoctorSystemId());
        }
        assertEquals(List.of("VIDEO", "IN_PERSON", "PHONE"), FeeSchedule.APPOINTMENT_TYPES);
        assertMoney("1500.00", feeScheduleRepository.findByDoctorSystemIdIsNullAndAppointmentType("VIDEO")
                .orElseThrow().getAmount());

        FeeSchedule saved = feeScheduleRepository.saveAndFlush(FeeSchedule.builder()
                .doctorSystemId(doctor.getSystemId()).appointmentType("VIDEO").amount(money("2500.00")).build());
        feeScheduleRepository.saveAndFlush(FeeSchedule.builder().doctorSystemId(doctor.getSystemId())
                .appointmentType("PHONE").amount(money("0.00")).isActive(false).build());
        entityManager.clear();

        FeeSchedule read = feeScheduleRepository.findById(saved.getFeeId()).orElseThrow();
        assertEquals(doctor.getSystemId(), read.getDoctorSystemId());
        assertEquals("VIDEO", read.getAppointmentType());
        assertMoney("2500.00", read.getAmount());
        assertEquals("KES", read.getCurrency());
        assertTrue(read.getIsActive());
        assertNotNull(read.getCreatedAt());
        assertNotNull(read.getUpdatedAt());

        assertEquals(2, feeScheduleRepository.findByDoctorSystemId(doctor.getSystemId()).size());
        assertEquals(read.getFeeId(), feeScheduleRepository
                .findByDoctorSystemIdAndAppointmentType(doctor.getSystemId(), "VIDEO").orElseThrow().getFeeId());
        assertTrue(feeScheduleRepository.findByDoctorSystemIdAndAppointmentType(otherDoctor.getSystemId(), "VIDEO").isEmpty());
        assertEquals(2, feeScheduleRepository.findByDoctorSystemIdIn(List.of(doctor.getSystemId(), otherDoctor.getSystemId())).size());
        // Active rows that can price this doctor's visits: the three defaults and the one active override.
        assertEquals(4, feeScheduleRepository.findActiveForDoctor(doctor.getSystemId()).size());
        assertEquals(3, feeScheduleRepository.findActiveForDoctor(otherDoctor.getSystemId()).size());

        // One row per (doctor, appointment type); the clinic default counts as one owner.
        assertRejected("ux_billing_fee_schedule_owner_type", () -> jdbc.update(
                "INSERT INTO billing_fee_schedule (doctor_system_id, appointment_type, amount) VALUES (?, 'VIDEO', 1)",
                doctor.getSystemId()));
        assertRejected("ux_billing_fee_schedule_owner_type", () -> jdbc.update(
                "INSERT INTO billing_fee_schedule (doctor_system_id, appointment_type, amount) VALUES (NULL, 'VIDEO', 1)"));
        assertRejected("billing_fee_schedule_amount_check", () -> jdbc.update(
                "INSERT INTO billing_fee_schedule (doctor_system_id, appointment_type, amount) VALUES (?, 'IN_PERSON', -0.01)",
                doctor.getSystemId()));
        assertRejected("billing_fee_schedule_appointment_type_check", () -> jdbc.update(
                "INSERT INTO billing_fee_schedule (doctor_system_id, appointment_type, amount) VALUES (?, 'HOME_VISIT', 1)",
                doctor.getSystemId()));
    }

    // ==================== CONSTRAINTS ====================

    @Test
    void paymentMethods_allowOneUsableDefaultPerOwner() {
        PaymentMethod first = paymentMethodRepository.saveAndFlush(PaymentMethod.builder()
                .systemId(patient.getSystemId()).type(PaymentMethod.TYPE_CARD).provider("Visa").lastFour("4242")
                .isDefault(true).build());

        assertRejected("ux_payment_methods_default", () -> insertMethod(patient, "MPESA", true, true, false));

        // Not constrained: a second method that is not the default, a default that was removed or switched off,
        // and the default of another owner.
        insertMethod(patient, "MPESA", false, true, false);
        insertMethod(patient, "CARD", true, true, true);
        insertMethod(patient, "CARD", true, false, false);
        insertMethod(otherPatient, "CARD", true, true, false);

        assertEquals(first.getMethodId(), paymentMethodRepository.findDefaultBySystemId(patient.getSystemId())
                .orElseThrow().getMethodId());
        List<PaymentMethod> usable = paymentMethodRepository.findUsableBySystemId(patient.getSystemId());
        assertEquals(2, usable.size());
        assertEquals(first.getMethodId(), usable.get(0).getMethodId(), "the default comes first");
        assertEquals(2, paymentMethodRepository.countUsableBySystemId(patient.getSystemId()));

        // Switching the default: clear the flag first, then another method can take it.
        Integer mpesaId = usable.get(1).getMethodId();
        assertEquals(3, paymentMethodRepository.clearDefault(patient.getSystemId()));
        assertTrue(paymentMethodRepository.findDefaultBySystemId(patient.getSystemId()).isEmpty());
        PaymentMethod mpesa = paymentMethodRepository.findUsableByMethodIdAndSystemId(mpesaId, patient.getSystemId()).orElseThrow();
        mpesa.setIsDefault(true);
        paymentMethodRepository.saveAndFlush(mpesa);
        assertEquals(mpesaId, paymentMethodRepository.findDefaultBySystemId(patient.getSystemId()).orElseThrow().getMethodId());
        // The other owner's default is untouched.
        assertTrue(paymentMethodRepository.findDefaultBySystemId(otherPatient.getSystemId()).isPresent());

        // A method is found only for its own owner.
        assertTrue(paymentMethodRepository.findByMethodIdAndSystemId(mpesaId, otherPatient.getSystemId()).isEmpty());
        assertTrue(paymentMethodRepository.findUsableByMethodIdAndSystemId(mpesaId, otherPatient.getSystemId()).isEmpty());
    }

    @Test
    void paymentMethods_acceptTheFiveTypes_andRefuseCrypto() {
        for (String type : PaymentMethod.TYPES) {
            insertMethod(patient, type, false, true, false);
        }
        assertEquals(List.of("CARD", "INSURANCE", "CASH", "MPESA", "BANK_TRANSFER"), PaymentMethod.TYPES);
        assertRejected("payment_methods_type_allowed", () -> insertMethod(patient, "CRYPTO", false, true, false));
        // The owner is required and must be an account.
        assertRejected("system_id", () -> jdbc.update("INSERT INTO payment_methods (type) VALUES ('CARD')"));
        assertRejected("payment_methods_system_id_fkey", () -> jdbc.update(
                "INSERT INTO payment_methods (system_id, type) VALUES ('PAT-00000', 'CARD')"));
    }

    @Test
    void invoices_allowOneNonCancelledInvoicePerAppointment() {
        Integer appointmentId = appointment(patient, doctor);
        insertInvoice("INV-2026-491001", appointmentId, null, "SENT");

        assertRejected("ux_invoices_appointment_active",
                () -> insertInvoice("INV-2026-491002", appointmentId, null, "DRAFT"));

        // A REFUNDED invoice still counts as the visit's invoice.
        jdbc.update("UPDATE invoices SET status = 'REFUNDED' WHERE invoice_no = 'INV-2026-491001'");
        assertRejected("ux_invoices_appointment_active",
                () -> insertInvoice("INV-2026-491002", appointmentId, null, "DRAFT"));

        // A CANCELLED invoice frees the visit, and any number of cancelled invoices may exist for it.
        jdbc.update("UPDATE invoices SET status = 'CANCELLED' WHERE invoice_no = 'INV-2026-491001'");
        insertInvoice("INV-2026-491002", appointmentId, null, "CANCELLED");
        insertInvoice("INV-2026-491003", appointmentId, null, "DRAFT");
        assertEquals("INV-2026-491003", invoiceRepository.findActiveByAppointmentId(appointmentId).orElseThrow().getInvoiceNo());
        assertEquals(1, invoiceRepository.findActiveByAppointmentIdIn(List.of(appointmentId)).size());

        // Invoices without a visit are not constrained.
        insertInvoice("INV-2026-491004", null, null, "SENT");
        insertInvoice("INV-2026-491005", null, null, "SENT");
        // Invoice numbers are unique.
        assertRejected("invoices_invoice_no_key", () -> insertInvoice("INV-2026-491005", null, null, "DRAFT"));
    }

    @Test
    void invoices_allowOneNonCancelledInvoicePerConsultation() {
        Integer appointmentId = appointment(patient, doctor);
        Integer consultationId = consultation(appointmentId, patient, doctor);
        insertInvoice("INV-2026-492001", null, consultationId, "PAID");

        assertRejected("ux_invoices_consultation_active",
                () -> insertInvoice("INV-2026-492002", null, consultationId, "DRAFT"));
        jdbc.update("UPDATE invoices SET status = 'REFUNDED' WHERE invoice_no = 'INV-2026-492001'");
        assertRejected("ux_invoices_consultation_active",
                () -> insertInvoice("INV-2026-492002", null, consultationId, "SENT"));

        jdbc.update("UPDATE invoices SET status = 'CANCELLED' WHERE invoice_no = 'INV-2026-492001'");
        insertInvoice("INV-2026-492002", null, consultationId, "SENT");
        assertEquals("INV-2026-492002", invoiceRepository.findActiveByConsultationId(consultationId).orElseThrow().getInvoiceNo());
        assertEquals(1, invoiceRepository.findActiveByConsultationIdIn(List.of(consultationId)).size());
        assertTrue(invoiceRepository.findActiveByConsultationId(-1).isEmpty());
    }

    @Test
    void invoices_refuseUnknownStatuses_negativeAmounts_andOverpayment() {
        for (String status : Invoice.STATUSES) {
            insertInvoice("INV-2026-493-" + status, null, null, status);
        }
        assertEquals(List.of("DRAFT", "SENT", "PARTIALLY_PAID", "PAID", "OVERDUE", "CANCELLED", "REFUNDED"), Invoice.STATUSES);
        assertRejected("invoices_status_allowed", () -> insertInvoice("INV-2026-493900", null, null, "VOID"));

        for (String column : List.of("subtotal", "tax", "discount", "amount_paid")) {
            assertRejected("invoices_amounts_not_negative", () -> jdbc.update(
                    "INSERT INTO invoices (patient_system_id, invoice_no, " + column + ") VALUES (?, 'INV-2026-493901', -0.01)",
                    patient.getSystemId()));
        }
        // A negative total breaks two rules at once (it is also below the amount paid); either one refuses it.
        assertRejected("invoices_amount", () -> jdbc.update(
                "INSERT INTO invoices (patient_system_id, invoice_no, total) VALUES (?, 'INV-2026-493901', -0.01)",
                patient.getSystemId()));
        assertRejected("invoices_amounts_not_negative", () -> jdbc.update(
                "UPDATE invoices SET total = -0.01, amount_paid = -0.02 WHERE invoice_no = 'INV-2026-493-DRAFT'"));
        assertRejected("invoices_amount_paid_within_total", () -> jdbc.update(
                "INSERT INTO invoices (patient_system_id, invoice_no, total, amount_paid) VALUES (?, 'INV-2026-493902', 100.00, 100.01)",
                patient.getSystemId()));
        jdbc.update("INSERT INTO invoices (patient_system_id, invoice_no, total, amount_paid) VALUES (?, 'INV-2026-493903', 100.00, 100.00)",
                patient.getSystemId());

        // The patient is required and must be an account; the legacy integer key is not.
        assertRejected("patient_system_id", () -> jdbc.update("INSERT INTO invoices (invoice_no) VALUES ('INV-2026-493904')"));
        assertRejected("invoices_patient_system_id_fkey", () -> jdbc.update(
                "INSERT INTO invoices (patient_system_id, invoice_no) VALUES ('PAT-00000', 'INV-2026-493905')"));
        assertRejected("invoices_doctor_system_id_fkey", () -> jdbc.update(
                "INSERT INTO invoices (patient_system_id, doctor_system_id, invoice_no) VALUES (?, 'DOC-00000', 'INV-2026-493906')",
                patient.getSystemId()));
    }

    @Test
    void payments_requireAPositiveAmount_aKnownMethod_andARefundWithinTheAmount() {
        Invoice invoice = savedInvoice("INV-2026-494001", Invoice.STATUS_SENT, null, null);
        for (String method : PaymentMethod.TYPES) {
            insertPayment(invoice, "0.01", method, "PENDING", "0.00");
        }

        assertRejected("payments_amount_positive", () -> insertPayment(invoice, "0.00", "CASH", "PENDING", "0.00"));
        assertRejected("payments_amount_positive", () -> insertPayment(invoice, "-5.00", "CASH", "PENDING", "0.00"));
        assertRejected("payments_method_allowed", () -> insertPayment(invoice, "5.00", "CRYPTO", "PENDING", "0.00"));
        assertRejected("method", () -> jdbc.update(
                "INSERT INTO payments (patient_system_id, invoice_id, amount) VALUES (?, ?, 5.00)",
                patient.getSystemId(), invoice.getInvoiceId()));
        assertRejected("payments_refunded_amount_range", () -> insertPayment(invoice, "5.00", "CASH", "REFUNDED", "5.01"));
        assertRejected("payments_refunded_amount_range", () -> insertPayment(invoice, "5.00", "CASH", "COMPLETED", "-0.01"));
        insertPayment(invoice, "5.00", "CASH", "REFUNDED", "5.00");
        assertRejected("payments_status_check", () -> insertPayment(invoice, "5.00", "CASH", "SETTLED", "0.00"));
        assertRejected("payments_patient_system_id_fkey", () -> jdbc.update(
                "INSERT INTO payments (patient_system_id, amount, method) VALUES ('PAT-00000', 5.00, 'CASH')"));
        assertEquals(List.of("PENDING", "COMPLETED", "FAILED", "REFUNDED", "CANCELLED"), Payment.STATUSES);
    }

    @Test
    void insuranceClaims_allowOneOpenClaimPerInvoice_andRequireAPositiveAmount() {
        Invoice invoice = savedInvoice("INV-2026-495001", Invoice.STATUS_SENT, null, null);
        Invoice otherInvoice = savedInvoice("INV-2026-495002", Invoice.STATUS_SENT, null, null);
        insertClaim(invoice, "CLM-2026-495001", "SUBMITTED", "100.00");

        // SUBMITTED, PENDING and APPEALED are open.
        for (String open : InsuranceClaim.OPEN_STATUSES) {
            assertRejected("ux_insurance_claims_open", () -> insertClaim(invoice, "CLM-2026-495002", open, "100.00"));
        }
        assertEquals(List.of("SUBMITTED", "PENDING", "APPEALED"), InsuranceClaim.OPEN_STATUSES);
        assertTrue(insuranceClaimRepository.existsByInvoiceIdAndStatusIn(invoice.getInvoiceId(), InsuranceClaim.OPEN_STATUSES));
        assertFalse(insuranceClaimRepository.existsByInvoiceIdAndStatusIn(otherInvoice.getInvoiceId(), InsuranceClaim.OPEN_STATUSES));

        // A decided claim may sit next to the open one, and another invoice has its own open claim.
        insertClaim(invoice, "CLM-2026-495003", "REJECTED", "100.00");
        insertClaim(invoice, "CLM-2026-495004", "PARTIAL", "100.00");
        insertClaim(invoice, "CLM-2026-495005", "APPROVED", "100.00");
        insertClaim(otherInvoice, "CLM-2026-495006", "PENDING", "100.00");

        // Once the open claim is decided, a new one can be opened; reopening the old one is then refused.
        jdbc.update("UPDATE insurance_claims SET status = 'REJECTED' WHERE claim_no = 'CLM-2026-495001'");
        insertClaim(invoice, "CLM-2026-495007", "SUBMITTED", "100.00");
        assertRejected("ux_insurance_claims_open", () -> jdbc.update(
                "UPDATE insurance_claims SET status = 'APPEALED' WHERE claim_no = 'CLM-2026-495001'"));
        assertEquals("CLM-2026-495007", insuranceClaimRepository
                .findFirstByInvoiceIdAndStatusInOrderByClaimIdDesc(invoice.getInvoiceId(), InsuranceClaim.OPEN_STATUSES)
                .orElseThrow().getClaimNo());
        assertEquals(5, insuranceClaimRepository.findByInvoiceIdOrderBySubmittedAtDescClaimIdDesc(invoice.getInvoiceId()).size());

        assertRejected("insurance_claims_claim_amount_positive", () -> insertClaim(otherInvoice, "CLM-2026-495008", "REJECTED", "0.00"));
        assertRejected("insurance_claims_claim_amount_positive", () -> insertClaim(otherInvoice, "CLM-2026-495008", "REJECTED", "-1.00"));
        assertRejected("insurance_claims_approved_amount_not_negative", () -> jdbc.update(
                "UPDATE insurance_claims SET approved_amount = -0.01 WHERE claim_no = 'CLM-2026-495005'"));
        assertRejected("insurance_claims_status_allowed", () -> insertClaim(otherInvoice, "CLM-2026-495008", "WITHDRAWN", "1.00"));
        assertRejected("insurance_claims_claim_no_key", () -> insertClaim(otherInvoice, "CLM-2026-495005", "REJECTED", "1.00"));
        assertEquals(List.of("SUBMITTED", "PENDING", "APPROVED", "REJECTED", "PARTIAL", "APPEALED"), InsuranceClaim.STATUSES);
    }

    @Test
    void updatedAt_isMaintainedByTheTrigger_onTheFourTablesThatHaveIt() {
        assertEquals(4L, jdbc.queryForObject("SELECT COUNT(*) FROM pg_trigger WHERE NOT tgisinternal AND tgname IN ("
                + "'update_invoices_updated_at', 'update_payments_updated_at', 'update_insurance_claims_updated_at', "
                + "'update_billing_fee_schedule_updated_at')", Long.class));

        Invoice invoice = savedInvoice("INV-2026-496001", Invoice.STATUS_DRAFT, null, null);
        // Whatever an UPDATE writes into updated_at, the trigger replaces it with the current time.
        jdbc.update("UPDATE invoices SET notes = 'changed', updated_at = TIMESTAMP '2000-01-01 00:00:00' WHERE invoice_id = ?",
                invoice.getInvoiceId());
        LocalDateTime updatedAt = jdbc.queryForObject("SELECT updated_at FROM invoices WHERE invoice_id = ?",
                LocalDateTime.class, invoice.getInvoiceId());
        assertNotNull(updatedAt);
        assertTrue(updatedAt.isAfter(LocalDateTime.now().minusHours(1)), "updated_at was " + updatedAt);

        // An update through the entity keeps the row valid as well.
        entityManager.clear();
        Invoice managed = invoiceRepository.findById(invoice.getInvoiceId()).orElseThrow();
        managed.setStatus(Invoice.STATUS_SENT);
        invoiceRepository.saveAndFlush(managed);
        assertNotNull(managed.getUpdatedAt());
    }

    // ==================== REPOSITORY QUERIES ====================

    @Test
    void invoiceFinders_pageByPatientDoctorAndStatus() {
        savedInvoice("INV-2026-497001", Invoice.STATUS_DRAFT, patient, doctor);
        savedInvoice("INV-2026-497002", Invoice.STATUS_SENT, patient, doctor);
        savedInvoice("INV-2026-497003", Invoice.STATUS_OVERDUE, patient, otherDoctor);
        savedInvoice("INV-2026-497004", Invoice.STATUS_PAID, otherPatient, doctor);
        String p = patient.getSystemId();
        String d = doctor.getSystemId();

        Page<Invoice> byPatient = invoiceRepository.findByPatientSystemId(p, NEWEST_FIRST);
        assertEquals(3, byPatient.getTotalElements());
        // createdAt descending, then invoiceId descending.
        assertEquals(List.of("INV-2026-497003", "INV-2026-497002", "INV-2026-497001"), numbers(byPatient));

        assertEquals(List.of("INV-2026-497003", "INV-2026-497002"), numbers(
                invoiceRepository.findByPatientSystemIdAndStatusIn(p, Invoice.PAYABLE_STATUSES, NEWEST_FIRST)));
        assertEquals(3, invoiceRepository.findByDoctorSystemId(d, NEWEST_FIRST).getTotalElements());
        assertEquals(List.of("INV-2026-497004"), numbers(
                invoiceRepository.findByDoctorSystemIdAndStatusIn(d, List.of(Invoice.STATUS_PAID), NEWEST_FIRST)));
        assertEquals(List.of("INV-2026-497002", "INV-2026-497001"), numbers(
                invoiceRepository.findByPatientSystemIdAndDoctorSystemId(p, d, NEWEST_FIRST)));
        assertEquals(List.of("INV-2026-497001"), numbers(invoiceRepository
                .findByPatientSystemIdAndDoctorSystemIdAndStatusIn(p, d, List.of(Invoice.STATUS_DRAFT), NEWEST_FIRST)));
        assertTrue(numbers(invoiceRepository.findByStatusIn(List.of(Invoice.STATUS_OVERDUE), NEWEST_FIRST))
                .contains("INV-2026-497003"));

        // Pages are bounded.
        Page<Invoice> firstPage = invoiceRepository.findByPatientSystemId(p,
                PageRequest.of(0, 2, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("invoiceId"))));
        assertEquals(2, firstPage.getContent().size());
        assertEquals(3, firstPage.getTotalElements());
        assertEquals(2, firstPage.getTotalPages());

        // Optional filters combine through specifications; a null filter matches everything.
        assertEquals(List.of("INV-2026-497002"), numbers(invoiceRepository.findAll(
                InvoiceSpecifications.patient(p).and(InvoiceSpecifications.doctor(d))
                        .and(InvoiceSpecifications.statusIn(Invoice.PAYABLE_STATUSES))
                        .and(InvoiceSpecifications.appointment(null)).and(InvoiceSpecifications.consultation(null)),
                NEWEST_FIRST)));
        assertEquals(3, invoiceRepository.findAll(InvoiceSpecifications.patient(p)
                .and(InvoiceSpecifications.doctor(null)).and(InvoiceSpecifications.statusIn(null)), NEWEST_FIRST)
                .getTotalElements());
        // issued(): only invoices that were sent at some point (the DRAFT one never was).
        assertEquals(List.of("INV-2026-497003", "INV-2026-497002"), numbers(invoiceRepository.findAll(
                InvoiceSpecifications.patient(p).and(InvoiceSpecifications.issued()), NEWEST_FIRST)));

        assertEquals(2, invoiceRepository.findByInvoiceIdIn(byPatient.getContent().stream().limit(2)
                .map(Invoice::getInvoiceId).toList()).size());
    }

    @Test
    void invoiceSpecifications_filterByVisit() {
        Integer appointmentId = appointment(patient, doctor);
        Integer consultationId = consultation(appointmentId, patient, doctor);
        Invoice linked = invoiceRepository.saveAndFlush(Invoice.builder().patientSystemId(patient.getSystemId())
                .doctorSystemId(doctor.getSystemId()).invoiceNo("INV-2026-497101")
                .appointmentId(appointmentId).consultationId(consultationId).build());
        savedInvoice("INV-2026-497102", Invoice.STATUS_DRAFT, patient, doctor);

        assertEquals(List.of(linked.getInvoiceNo()), numbers(invoiceRepository.findAll(
                InvoiceSpecifications.doctor(doctor.getSystemId()).and(InvoiceSpecifications.consultation(consultationId)),
                NEWEST_FIRST)));
        assertEquals(List.of(linked.getInvoiceNo()), numbers(invoiceRepository.findAll(
                InvoiceSpecifications.doctor(doctor.getSystemId()).and(InvoiceSpecifications.appointment(appointmentId)),
                NEWEST_FIRST)));
    }

    @Test
    void findByIdForUpdate_locksTheInvoiceAndThePaymentRow() {
        Invoice invoice = savedInvoice("INV-2026-498001", Invoice.STATUS_SENT, null, null);
        Payment payment = paymentRepository.saveAndFlush(Payment.builder().patientSystemId(patient.getSystemId())
                .invoiceId(invoice.getInvoiceId()).amount(money("10.00")).method(PaymentMethod.TYPE_CASH).build());
        InsuranceClaim claim = insuranceClaimRepository.saveAndFlush(InsuranceClaim.builder()
                .patientSystemId(patient.getSystemId()).invoiceId(invoice.getInvoiceId()).insuranceProvider("AAR")
                .policyNumber("P-1").claimAmount(money("10.00")).build());
        entityManager.clear();

        Invoice lockedInvoice = invoiceRepository.findByIdForUpdate(invoice.getInvoiceId()).orElseThrow();
        assertEquals(LockModeType.PESSIMISTIC_WRITE, entityManager.getLockMode(lockedInvoice));
        Payment lockedPayment = paymentRepository.findByIdForUpdate(payment.getPaymentId()).orElseThrow();
        assertEquals(LockModeType.PESSIMISTIC_WRITE, entityManager.getLockMode(lockedPayment));
        InsuranceClaim lockedClaim = insuranceClaimRepository.findByIdForUpdate(claim.getClaimId()).orElseThrow();
        assertEquals(LockModeType.PESSIMISTIC_WRITE, entityManager.getLockMode(lockedClaim));

        assertTrue(invoiceRepository.findByIdForUpdate(-1).isEmpty());
        assertTrue(paymentRepository.findByIdForUpdate(-1).isEmpty());
    }

    @Test
    void invoiceNumbers_comeFromTheSequence_andOverdueCandidatesAreSelectedByDueDate() {
        long first = invoiceRepository.nextInvoiceNumber();
        assertTrue(first >= 1);
        assertEquals(first + 1, invoiceRepository.nextInvoiceNumber());

        LocalDate today = LocalDate.of(2026, 11, 25);
        Integer dueYesterday = dueInvoice("INV-2026-498101", Invoice.STATUS_SENT, today.minusDays(1));
        Integer partDueLastWeek = dueInvoice("INV-2026-498102", Invoice.STATUS_PARTIALLY_PAID, today.minusDays(7));
        dueInvoice("INV-2026-498103", Invoice.STATUS_SENT, today);                 // due today: not yet
        dueInvoice("INV-2026-498104", Invoice.STATUS_PAID, today.minusDays(30));   // settled
        dueInvoice("INV-2026-498105", Invoice.STATUS_OVERDUE, today.minusDays(30)); // already overdue
        dueInvoice("INV-2026-498106", Invoice.STATUS_DRAFT, today.minusDays(30));  // never issued
        dueInvoice("INV-2026-498107", Invoice.STATUS_SENT, null);                  // legacy row without a due date

        List<Integer> candidates = new ArrayList<>(invoiceRepository.findIdsDueBefore(today, PageRequest.of(0, 200)));
        candidates.retainAll(List.of(dueYesterday, partDueLastWeek));
        // Oldest due date first.
        assertEquals(List.of(partDueLastWeek, dueYesterday), candidates);
        List<Integer> mine = invoiceRepository.findByPatientSystemId(patient.getSystemId(), PageRequest.of(0, 50))
                .map(Invoice::getInvoiceId).getContent();
        List<Integer> all = new ArrayList<>(invoiceRepository.findIdsDueBefore(today, PageRequest.of(0, 200)));
        all.retainAll(mine);
        assertEquals(2, all.size(), "only SENT and PARTIALLY_PAID invoices past their due date are candidates");
        // With a grace period the cutoff moves back.
        List<Integer> withGrace = new ArrayList<>(invoiceRepository.findIdsDueBefore(today.minusDays(3), PageRequest.of(0, 200)));
        withGrace.retainAll(mine);
        assertEquals(List.of(partDueLastWeek), withGrace);
        assertEquals(1, invoiceRepository.findIdsDueBefore(today, PageRequest.of(0, 1)).size());
    }

    @Test
    void paymentFinders_pageByPatientAndStatus_andSupportTheGatewayLookups() {
        Invoice invoice = savedInvoice("INV-2026-499001", Invoice.STATUS_SENT, null, null);
        Invoice otherInvoice = savedInvoice("INV-2026-499002", Invoice.STATUS_SENT, otherPatient, doctor);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        Payment old = payment(invoice, patient, "100.00", Payment.STATUS_PENDING, "local", "local_pay_old", now.minusMinutes(45));
        Payment recent = payment(invoice, patient, "250.00", Payment.STATUS_PENDING, "local", "local_pay_recent", now.minusMinutes(5));
        Payment done = payment(invoice, patient, "300.00", Payment.STATUS_COMPLETED, "MANUAL", "BANK-REF-1", now.minusMinutes(60));
        done.setTransactionId("LOC-AAAA1111BBBB");
        paymentRepository.saveAndFlush(done);
        payment(invoice, patient, "50.00", Payment.STATUS_COMPLETED, "MANUAL", "BANK-REF-1", now.minusMinutes(50));
        payment(otherInvoice, otherPatient, "75.00", Payment.STATUS_PENDING, "local", "local_pay_other", now.minusMinutes(40));
        Pageable newest = PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("paymentId")));

        assertEquals(4, paymentRepository.findByPatientSystemId(patient.getSystemId(), newest).getTotalElements());
        assertEquals(2, paymentRepository.findByPatientSystemIdAndStatusIn(patient.getSystemId(),
                List.of(Payment.STATUS_PENDING), newest).getTotalElements());
        assertEquals(1, paymentRepository.findByPatientSystemId(otherPatient.getSystemId(), newest).getTotalElements());
        assertEquals(4, paymentRepository.findByInvoiceId(invoice.getInvoiceId(), newest).getTotalElements());
        assertEquals(recent.getPaymentId(), paymentRepository
                .findByInvoiceIdOrderByCreatedAtDescPaymentIdDesc(invoice.getInvoiceId()).get(0).getPaymentId());
        assertEquals(2, paymentRepository.findByInvoiceIdAndStatus(invoice.getInvoiceId(), Payment.STATUS_PENDING).size());
        assertTrue(paymentRepository.findByStatusIn(List.of(Payment.STATUS_PENDING), newest).getTotalElements() >= 3);

        // What the pending payments of an invoice hold back; zero when there is none.
        assertMoney("350.00", paymentRepository.sumPendingAmountByInvoiceId(invoice.getInvoiceId()));
        assertEquals(0, BigDecimal.ZERO.compareTo(paymentRepository.sumPendingAmountByInvoiceId(-1)));

        // Gateway callback lookup; a typed manual reference may repeat and resolves to the newest row.
        assertEquals(old.getPaymentId(), paymentRepository
                .findFirstByGatewayAndProviderReferenceOrderByPaymentIdDesc("local", "local_pay_old").orElseThrow().getPaymentId());
        assertTrue(paymentRepository.findFirstByGatewayAndProviderReferenceOrderByPaymentIdDesc("MANUAL", "local_pay_old").isEmpty());
        assertTrue(paymentRepository.findFirstByGatewayAndProviderReferenceOrderByPaymentIdDesc("MANUAL", "BANK-REF-1").isPresent());
        assertEquals(done.getPaymentId(), paymentRepository.findByTransactionId("LOC-AAAA1111BBBB").orElseThrow().getPaymentId());
        assertTrue(paymentRepository.existsByTransactionId("LOC-AAAA1111BBBB"));
        assertFalse(paymentRepository.existsByTransactionId("LOC-UNKNOWN"));
        // A confirmation code belongs to one payment.
        assertRejected("payments_transaction_id_key", () -> jdbc.update(
                "UPDATE payments SET transaction_id = 'LOC-AAAA1111BBBB' WHERE payment_id = ?", old.getPaymentId()));

        // Reconciliation: PENDING payments older than the cutoff, oldest first.
        List<Integer> stale = new ArrayList<>(paymentRepository.findPendingIdsCreatedBefore(now.minusMinutes(30), PageRequest.of(0, 200)));
        stale.retainAll(List.of(old.getPaymentId(), recent.getPaymentId(), done.getPaymentId()));
        assertEquals(List.of(old.getPaymentId()), stale);
    }

    @Test
    void claimFinders_pageByPatientAndStatus() {
        Invoice invoice = savedInvoice("INV-2026-499101", Invoice.STATUS_SENT, null, null);
        Invoice otherInvoice = savedInvoice("INV-2026-499102", Invoice.STATUS_SENT, otherPatient, doctor);
        insertClaim(invoice, "CLM-2026-499101", "REJECTED", "10.00");
        insertClaim(invoice, "CLM-2026-499102", "PENDING", "10.00");
        jdbc.update("INSERT INTO insurance_claims (patient_system_id, invoice_id, claim_no, insurance_provider, policy_number, claim_amount, status) "
                + "VALUES (?, ?, 'CLM-2026-499103', 'AAR', 'P-2', 10.00, 'PENDING')", otherPatient.getSystemId(), otherInvoice.getInvoiceId());
        Pageable newest = PageRequest.of(0, 10, Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.desc("claimId")));

        assertEquals(2, insuranceClaimRepository.findByPatientSystemId(patient.getSystemId(), newest).getTotalElements());
        Page<InsuranceClaim> open = insuranceClaimRepository.findByPatientSystemIdAndStatusIn(patient.getSystemId(),
                InsuranceClaim.OPEN_STATUSES, newest);
        assertEquals(1, open.getTotalElements());
        assertEquals("CLM-2026-499102", open.getContent().get(0).getClaimNo());
        assertTrue(insuranceClaimRepository.findByStatusIn(List.of(InsuranceClaim.STATUS_PENDING), newest).getTotalElements() >= 2);
    }

    // ==================== PROPERTIES ====================

    @Test
    void billingProperties_haveWorkingCodeDefaults_andNeverPrintTheSecret() {
        BillingProperties defaults = new BillingProperties();
        assertEquals("KES", defaults.getDefaultCurrency());
        assertEquals(0, BigDecimal.ZERO.compareTo(defaults.getTaxRate()));
        assertEquals(14, defaults.getPaymentTermsDays());
        assertEquals("DRAFT", defaults.getAutoInvoice());
        assertTrue(defaults.isAutoInvoiceEnabled());
        assertEquals("local", defaults.getProvider());
        assertTrue(defaults.isLocalProvider());
        assertEquals("manual", defaults.getClaimsProvider());
        assertTrue(defaults.isManualClaimsProvider());
        assertEquals(0, defaults.getOverdueGraceDays());
        assertEquals("dev-only-billing-webhook-secret-change-me", defaults.getLocal().getWebhookSecret());
        assertTrue(defaults.getLocal().isDevWebhookSecret());
        assertFalse(defaults.toString().contains(defaults.getLocal().getWebhookSecret()));
        assertFalse(defaults.getLocal().toString().contains(defaults.getLocal().getWebhookSecret()));

        // The bean bound from the test profile: its own secret, everything else at the defaults.
        assertEquals("KES", billingProperties.getDefaultCurrency());
        assertEquals("DRAFT", billingProperties.getAutoInvoice());
        assertEquals("local", billingProperties.getProvider());
        assertEquals("manual", billingProperties.getClaimsProvider());
        assertEquals(14, billingProperties.getPaymentTermsDays());
        assertTrue(billingProperties.getLocal().hasWebhookSecret());
        assertTrue(billingProperties.getLocal().isDevWebhookSecret());
        assertFalse(billingProperties.toString().contains(billingProperties.getLocal().getWebhookSecret()));

        // Normalisation and helpers.
        BillingProperties changed = new BillingProperties();
        changed.setAutoInvoice(" sent ");
        assertEquals(BillingProperties.AUTO_INVOICE_SENT, changed.getAutoInvoice());
        changed.setAutoInvoice("false"); // an unquoted OFF in YAML arrives as the boolean false
        assertEquals(BillingProperties.AUTO_INVOICE_OFF, changed.getAutoInvoice());
        assertFalse(changed.isAutoInvoiceEnabled());
        changed.setDefaultCurrency(" usd ");
        assertEquals("USD", changed.getDefaultCurrency());
        changed.setProvider(" Stripe ");
        assertEquals("stripe", changed.getProvider());
        assertFalse(changed.isLocalProvider());
        changed.setOverdueGraceDays(-3);
        assertEquals(0, changed.effectiveOverdueGraceDays());
        changed.setOverdueGraceDays(5);
        assertEquals(5, changed.effectiveOverdueGraceDays());
        changed.getLocal().setWebhookSecret("a-real-secret-from-the-environment");
        assertFalse(changed.getLocal().isDevWebhookSecret());
        changed.getLocal().setWebhookSecret("  ");
        assertFalse(changed.getLocal().hasWebhookSecret());
        assertFalse(changed.getLocal().isDevWebhookSecret());
    }

    @Test
    void billingProperties_bindFromConfiguration_andRefuseUnusableValues() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(BillingConfig.class);

        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("DRAFT", context.getBean(BillingProperties.class).getAutoInvoice());
        });
        runner.withPropertyValues(
                "carenexus.billing.default-currency=usd",
                "carenexus.billing.tax-rate=0.16",
                "carenexus.billing.payment-terms-days=30",
                "carenexus.billing.auto-invoice=off",
                "carenexus.billing.provider=local",
                "carenexus.billing.claims-provider=manual",
                "carenexus.billing.overdue-grace-days=2",
                "carenexus.billing.local.webhook-secret=").run(context -> {
            assertNull(context.getStartupFailure());
            BillingProperties bound = context.getBean(BillingProperties.class);
            assertEquals("USD", bound.getDefaultCurrency());
            assertEquals(0, new BigDecimal("0.16").compareTo(bound.getTaxRate()));
            assertEquals(30, bound.getPaymentTermsDays());
            assertEquals("OFF", bound.getAutoInvoice());
            assertEquals(2, bound.getOverdueGraceDays());
            // An empty secret (the value outside the developer profiles) is bound as it is; the gateway decides.
            assertFalse(bound.getLocal().hasWebhookSecret());
        });

        for (String invalid : List.of(
                "carenexus.billing.auto-invoice=WEEKLY",
                "carenexus.billing.tax-rate=16",
                "carenexus.billing.tax-rate=-0.1",
                "carenexus.billing.default-currency=KSH1",
                "carenexus.billing.payment-terms-days=-1",
                "carenexus.billing.provider=")) {
            runner.withPropertyValues(invalid).run(context ->
                    assertNotNull(context.getStartupFailure(), invalid + " must fail startup"));
        }
    }

    // ==================== HELPERS ====================

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
        assertEquals(2, actual.scale(), "money is read with scale 2");
    }

    private static List<String> numbers(Page<Invoice> page) {
        return page.getContent().stream().map(Invoice::getInvoiceNo).toList();
    }

    /**
     * Runs a statement the database must refuse, inside a savepoint so the test transaction stays usable, and
     * checks that the refusal names the expected constraint (or column).
     */
    private void assertRejected(String expectedInMessage, Runnable statement) {
        jdbc.execute("SAVEPOINT billing_schema_check");
        try {
            DataIntegrityViolationException refused = assertThrows(DataIntegrityViolationException.class, statement::run);
            String message = String.valueOf(refused.getMostSpecificCause().getMessage());
            assertTrue(message.contains(expectedInMessage), "expected '" + expectedInMessage + "' in: " + message);
        } finally {
            jdbc.execute("ROLLBACK TO SAVEPOINT billing_schema_check");
        }
    }

    private Integer appointment(User forPatient, User withDoctor) {
        return jdbc.queryForObject("INSERT INTO appointments (patient_system_id, doctor_system_id, scheduled_at, "
                        + "duration_minutes, type, status) VALUES (?, ?, ?, 30, 'VIDEO', 'COMPLETED') RETURNING appointment_id",
                Integer.class, forPatient.getSystemId(), withDoctor.getSystemId(),
                LocalDateTime.now().minusDays(1).truncatedTo(ChronoUnit.MINUTES));
    }

    private Integer consultation(Integer appointmentId, User forPatient, User withDoctor) {
        return jdbc.queryForObject("INSERT INTO consultations (appointment_id, patient_system_id, doctor_system_id, status) "
                        + "VALUES (?, ?, ?, 'COMPLETED') RETURNING consultation_id",
                Integer.class, appointmentId, forPatient.getSystemId(), withDoctor.getSystemId());
    }

    /** An invoice saved through the entity. Null patient / doctor = the standard fixture pair. */
    private Invoice savedInvoice(String invoiceNo, String status, User forPatient, User byDoctor) {
        User p = forPatient == null ? patient : forPatient;
        User d = byDoctor == null ? doctor : byDoctor;
        int sequence = Integer.parseInt(invoiceNo.substring(invoiceNo.length() - 3));
        LocalDateTime created = LocalDateTime.now().minusDays(1).truncatedTo(ChronoUnit.SECONDS).plusSeconds(sequence);
        return invoiceRepository.saveAndFlush(Invoice.builder()
                .patientSystemId(p.getSystemId()).doctorSystemId(d.getSystemId()).createdBySystemId(d.getSystemId())
                .invoiceNo(invoiceNo).lineItems(LINE_ITEMS)
                .subtotal(money("1500.00")).total(money("1500.00"))
                .status(status)
                .issuedAt(Invoice.STATUS_DRAFT.equals(status) ? null : created)
                .dueDate(Invoice.STATUS_DRAFT.equals(status) ? null : created.toLocalDate().plusDays(14))
                .createdAt(created)
                .build());
    }

    private Integer dueInvoice(String invoiceNo, String status, LocalDate dueDate) {
        Invoice invoice = savedInvoice(invoiceNo, status, null, null);
        invoice.setDueDate(dueDate);
        return invoiceRepository.saveAndFlush(invoice).getInvoiceId();
    }

    private Payment payment(Invoice invoice, User forPatient, String amount, String status, String gateway,
                            String providerReference, LocalDateTime createdAt) {
        return paymentRepository.saveAndFlush(Payment.builder().patientSystemId(forPatient.getSystemId())
                .invoiceId(invoice.getInvoiceId()).amount(money(amount)).method(PaymentMethod.TYPE_MPESA)
                .status(status).gateway(gateway).providerReference(providerReference).createdAt(createdAt).build());
    }

    private void insertInvoice(String invoiceNo, Integer appointmentId, Integer consultationId, String status) {
        jdbc.update("INSERT INTO invoices (patient_system_id, doctor_system_id, appointment_id, consultation_id, "
                        + "invoice_no, status) VALUES (?, ?, ?, ?, ?, ?)",
                patient.getSystemId(), doctor.getSystemId(), appointmentId, consultationId, invoiceNo, status);
    }

    private void insertMethod(User owner, String type, boolean isDefault, boolean isActive, boolean isDeleted) {
        jdbc.update("INSERT INTO payment_methods (system_id, type, is_default, is_active, is_deleted) VALUES (?, ?, ?, ?, ?)",
                owner.getSystemId(), type, isDefault, isActive, isDeleted);
    }

    private void insertPayment(Invoice invoice, String amount, String method, String status, String refundedAmount) {
        jdbc.update("INSERT INTO payments (patient_system_id, invoice_id, amount, method, status, refunded_amount) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                patient.getSystemId(), invoice.getInvoiceId(), new BigDecimal(amount), method, status,
                new BigDecimal(refundedAmount));
    }

    private void insertClaim(Invoice invoice, String claimNo, String status, String claimAmount) {
        jdbc.update("INSERT INTO insurance_claims (patient_system_id, invoice_id, claim_no, insurance_provider, "
                        + "policy_number, claim_amount, status) VALUES (?, ?, ?, 'Jubilee Health', 'JH-88213-K', ?, ?)",
                patient.getSystemId(), invoice.getInvoiceId(), claimNo, new BigDecimal(claimAmount), status);
    }
}
