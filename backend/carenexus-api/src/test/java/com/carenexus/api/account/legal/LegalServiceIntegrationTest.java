package com.carenexus.api.account.legal;

import com.carenexus.api.BaseIntegrationTest;
import com.carenexus.api.account.legal.dto.request.AcceptLegalRequest;
import com.carenexus.api.account.legal.dto.request.PublishLegalDocumentRequest;
import com.carenexus.api.account.legal.dto.response.LegalCurrentResponse;
import com.carenexus.api.account.legal.dto.response.LegalDocumentResponse;
import com.carenexus.api.account.legal.dto.response.LegalStatusResponse;
import com.carenexus.api.account.legal.service.LegalService;
import com.carenexus.api.account.legal.service.LegalSessionFlagContributor;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.common.event.UserRegisteredEvent;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service rules of be-account-legal, in a rolled-back transaction per test (so published versions never outlive
 * the test). The HTTP behaviour with committed transactions is covered by AccountLegalReleaseGateTest.
 */
class LegalServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private LegalService legalService;

    @Autowired
    private LegalSessionFlagContributor flagContributor;

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcTemplate jdbc;

    private User patient;
    private User caregiver;
    private User admin;

    @BeforeEach
    void setUp() {
        patient = createAndSaveUser(1, "PAT-00001", "thomas@example.com", "PATIENT");
        caregiver = createAndSaveUser(3, "CGV-00001", "sarah@example.com", "CAREGIVER");
        admin = createAndSaveUser(7, "ADM-00001", "admin@example.com", "ADMIN");
    }

    @AfterEach
    void tearDown() {
        clearAuthentication();
    }

    @Test
    void seededVersions_areCurrent_andMarkedPendingLegalReview() {
        LegalCurrentResponse current = legalService.current();

        assertEquals("1", current.terms().version());
        assertEquals("Terms of Service", current.terms().title());
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), current.terms().effectiveAt());
        assertTrue(current.terms().pendingLegalReview());
        assertEquals("1", current.privacy().version());
        assertEquals("Privacy Policy", current.privacy().title());
        assertTrue(current.privacy().pendingLegalReview());

        LegalDocumentResponse privacy = legalService.currentDocument(" Privacy ");
        assertEquals("PRIVACY", privacy.docType());
        assertTrue(privacy.contentMd().contains("# Privacy Policy"));
        assertThrows(ResourceNotFoundException.class, () -> legalService.currentDocument("cookies"));
        assertThrows(ResourceNotFoundException.class, () -> legalService.currentDocument(null));
    }

    @Test
    void accountsCreatedByACaregiverOrAnAdministrator_getNoRows_andMustAcceptAtFirstSignIn() {
        authenticateAs(caregiver);
        User dependent = authService.createActiveUserAccount("Grace", "Moyo", "grace@example.com",
                "Str0ng-Passw0rd", null, "PATIENT");
        authenticateAs(admin);
        User colleague = authService.createActiveUserAccount("Ruth", "Ncube", "ruth@example.com",
                "Str0ng-Passw0rd", null, "ADMIN", true, true);

        // Even when versions are present in the event, an account created by somebody else gets none.
        legalService.recordRegistrationAcceptances(new UserRegisteredEvent(dependent.getSystemId(), "PATIENT",
                "grace@example.com", "1", "1", "10.0.0.1", "agent", caregiver.getSystemId()));

        assertEquals(0, rowsOf(dependent));
        assertEquals(0, rowsOf(colleague));
        assertTrue(legalService.isActionRequired(dependent.getSystemId()));
        assertTrue(legalService.isActionRequired(colleague.getSystemId()));
    }

    @Test
    void registrationEvent_judgesEachVersionOnItsOwn() {
        String systemId = patient.getSystemId();

        // Nothing sent: nothing written.
        legalService.recordRegistrationAcceptances(new UserRegisteredEvent(systemId, "PATIENT", null,
                null, "  ", "10.0.0.1", "agent", null));
        assertEquals(0, rowsOf(patient));

        // One stale version refuses the whole registration, even if the other one is current.
        BadRequestException stale = assertThrows(BadRequestException.class, () ->
                legalService.recordRegistrationAcceptances(new UserRegisteredEvent(systemId, "PATIENT", null,
                        "1", "2", "10.0.0.1", "agent", null)));
        assertEquals("LEGAL_ACCEPTANCE_REQUIRED", stale.getCode());
        assertEquals("The terms or privacy policy changed. Review the current version and accept it.", stale.getMessage());
        assertEquals(0, rowsOf(patient));

        legalService.recordRegistrationAcceptances(new UserRegisteredEvent(systemId, "PATIENT", null,
                " 1 ", null, "2001:0db8:85a3:0000:0000:8a2e:0370:7334", "a".repeat(700), null));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT doc_type, version, ip_address, user_agent FROM legal_acceptances WHERE system_id = ?", systemId);
        assertEquals("TERMS", row.get("doc_type"));
        assertEquals("1", row.get("version"));
        assertEquals("2001:0db8:85a3:0000:0000:8a2e:0370:7334", row.get("ip_address"));
        assertEquals(500, ((String) row.get("user_agent")).length());
        assertTrue(legalService.isActionRequired(systemId), "privacy is still pending");
    }

    @Test
    void sessionFlag_followsTheCurrentVersions() {
        Map<String, Object> flags = new LinkedHashMap<>();
        flagContributor.contribute(patient, flags);
        assertEquals(Boolean.TRUE, flags.get("legalActionRequired"));

        authenticateAs(patient);
        legalService.accept(new AcceptLegalRequest("TERMS", "1"), "10.0.0.1", "agent");
        LegalService.AcceptOutcome outcome = legalService.accept(new AcceptLegalRequest("privacy", "1"), null, null);
        assertTrue(outcome.created());
        assertFalse(outcome.status().actionRequired());

        flags.clear();
        flagContributor.contribute(patient, flags);
        assertEquals(Boolean.FALSE, flags.get("legalActionRequired"));

        // A new terms version, effective at the same instant as the current one: the newer row wins the tie.
        authenticateAs(admin);
        legalService.publish(publishRequest("TERMS", "2", Instant.parse("2026-11-01T00:00:00Z")));
        assertEquals("2", legalService.current().terms().version());

        flags.clear();
        flagContributor.contribute(patient, flags);
        assertEquals(Boolean.TRUE, flags.get("legalActionRequired"));

        authenticateAs(patient);
        LegalStatusResponse status = legalService.myStatus();
        assertEquals(1, status.pending().size());
        assertEquals("2", status.pending().get(0).version());
        assertFalse(status.pending().get(0).pendingLegalReview());

        // No user, no flag.
        flags.clear();
        flagContributor.contribute(null, flags);
        assertNull(flags.get("legalActionRequired"));
    }

    @Test
    void accept_isIdempotent_andOnlyForTheCurrentVersion() {
        authenticateAs(patient);

        BadRequestException stale = assertThrows(BadRequestException.class, () ->
                legalService.accept(new AcceptLegalRequest("TERMS", "0"), null, null));
        assertEquals("LEGAL_ACCEPTANCE_REQUIRED", stale.getCode());
        assertEquals(0, rowsOf(patient));

        // The version is trimmed before the exact comparison.
        LegalService.AcceptOutcome first = legalService.accept(new AcceptLegalRequest("TERMS", " 1 "), null, null);
        assertTrue(first.created());
        assertEquals(1, first.status().pending().size());
        assertEquals("PRIVACY", first.status().pending().get(0).docType());

        assertEquals(1, rowsOf(patient));
        LegalService.AcceptOutcome repeat = legalService.accept(new AcceptLegalRequest("terms", "1"), null, null);
        assertFalse(repeat.created());
        assertEquals(1, repeat.status().accepted().size());
        assertTrue(repeat.status().accepted().get(0).current());
        assertEquals(1, rowsOf(patient));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'LEGAL_ACCEPT' "
                + "AND target_system_id = 'PAT-00001'", Long.class));
    }

    @Test
    void administrationMethods_requireTheAdministratorRole_inTheService() {
        for (User user : List.of(patient, caregiver)) {
            authenticateAs(user);
            assertThrows(AuthorizationException.class, () -> legalService.acceptancesOf(patient.getSystemId(), 0, 20));
            assertThrows(AuthorizationException.class, () -> legalService.publish(publishRequest("TERMS", "2", null)));
        }

        authenticateAs(admin);
        assertThrows(ResourceNotFoundException.class, () -> legalService.acceptancesOf("PAT-99999", 0, 20));
        assertEquals(0, legalService.acceptancesOf(patient.getSystemId(), 0, 20).getTotalElements());

        assertThrows(ConflictException.class, () -> legalService.publish(publishRequest("PRIVACY", "1", null)));
        BadRequestException future = assertThrows(BadRequestException.class, () ->
                legalService.publish(publishRequest("PRIVACY", "2", Instant.now().plusSeconds(3600))));
        assertEquals("effectiveAt: must be a date in the past or in the present", future.getMessage());

        LegalDocumentResponse published = legalService.publish(publishRequest("PRIVACY", "2", null));
        assertEquals("2", published.version());
        assertEquals(0, published.effectiveAt().getNano(), "stored in whole seconds");
        assertEquals("ADM-00001", jdbc.queryForObject(
                "SELECT published_by_system_id FROM legal_documents WHERE doc_type = 'PRIVACY' AND version = '2'",
                String.class));
    }

    private long rowsOf(User user) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM legal_acceptances WHERE system_id = ?", Long.class,
                user.getSystemId());
        return count == null ? 0 : count;
    }

    private static PublishLegalDocumentRequest publishRequest(String docType, String version, Instant effectiveAt) {
        PublishLegalDocumentRequest request = new PublishLegalDocumentRequest();
        request.setDocType(docType);
        request.setVersion(version);
        request.setTitle(docType.equals("TERMS") ? "Terms of Service" : "Privacy Policy");
        request.setContentMd("# Reviewed text\n\nThe reviewed version of this document.");
        request.setEffectiveAt(effectiveAt);
        return request;
    }
}
