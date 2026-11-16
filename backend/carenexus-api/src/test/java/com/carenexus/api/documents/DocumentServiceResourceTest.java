package com.carenexus.api.documents;

import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.documents.model.Document;
import com.carenexus.api.documents.repository.DocumentReferenceRepository;
import com.carenexus.api.documents.repository.DocumentRepository;
import com.carenexus.api.documents.repository.DocumentUploaderRepository;
import com.carenexus.api.documents.service.DocumentService;
import com.carenexus.api.storage.FileScanner;
import com.carenexus.api.storage.FileStorage;
import com.carenexus.api.storage.ScanResult;
import com.carenexus.api.storage.StoredFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resource handling of {@link DocumentService} that the integration tests cannot observe: an opened download stream
 * is closed when the method fails after opening it, and an upload scans and stores the file before it opens its
 * write transaction, deleting the stored file when that transaction fails.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentServiceResourceTest {

    private static final String PATIENT = "PAT-00001";

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentUploaderRepository uploaderRepository;
    @Mock private DocumentReferenceRepository referenceRepository;
    @Mock private AuthorizationService authorizationService;
    @Mock private AuditService auditService;
    @Mock private RateLimitGuard rateLimitGuard;
    @Mock private FileStorage fileStorage;
    @Mock private FileScanner fileScanner;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PlatformTransactionManager transactionManager;

    private DocumentService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-10T09:00:00Z"), ZoneOffset.UTC);
        service = new DocumentService(documentRepository, uploaderRepository, referenceRepository, authorizationService,
                auditService, rateLimitGuard, fileStorage, fileScanner, eventPublisher, clock, transactionManager);
        when(authorizationService.currentSystemId()).thenReturn(PATIENT);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    /** A stream that records whether it was closed. */
    private static final class TrackedStream extends ByteArrayInputStream {
        private boolean closed;

        TrackedStream() {
            super(DocumentFixtures.pdfBytes());
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private Document storedDocument() {
        return Document.builder()
                .documentId(7)
                .patientSystemId(PATIENT)
                .uploadedBySystemId(PATIENT)
                .documentType(Document.TYPE_OTHER)
                .title("Scan")
                .storageKey("documents/2026/11/0b6f3b0e-8f7c-4d39-9a8e-8d2f0c4d1f11")
                .originalFilename("scan.pdf")
                .contentType("application/pdf")
                .sizeBytes(10L)
                .isDeleted(false)
                .uploadedAt(LocalDateTime.of(2026, 11, 10, 9, 0))
                .build();
    }

    @Test
    void downloadClosesTheOpenedStreamWhenTheAuditFails() {
        Document document = storedDocument();
        when(documentRepository.findVisibleById(7)).thenReturn(Optional.of(document));
        TrackedStream content = new TrackedStream();
        when(fileStorage.open(document.getStorageKey())).thenReturn(content);
        doThrow(new IllegalStateException("audit table unavailable"))
                .when(auditService).logClinicalAccess("READ", DocumentService.RESOURCE, 7, PATIENT);

        assertThrows(IllegalStateException.class, () -> service.openDownload(7));
        assertTrue(content.closed, "the stream must be closed when openDownload fails after opening it");
    }

    @Test
    void downloadHandsTheOpenStreamToTheCallerOnSuccess() {
        Document document = storedDocument();
        when(documentRepository.findVisibleById(7)).thenReturn(Optional.of(document));
        TrackedStream content = new TrackedStream();
        when(fileStorage.open(document.getStorageKey())).thenReturn(content);

        DocumentService.DocumentDownload download = service.openDownload(7);
        assertSame(content, download.content());
        assertEquals(10L, download.sizeBytes());
        assertTrue(!content.closed, "the caller (response converter) closes the stream");
        verify(authorizationService).requirePatientAccess(PATIENT, PatientPermission.DOCUMENTS, 7);
    }

    @Test
    void uploadScansAndStoresBeforeItsWriteTransactionAndDeletesTheFileWhenTheWriteFails() {
        when(referenceRepository.patientExists(PATIENT)).thenReturn(true);
        when(fileScanner.scan(any(InputStream.class), anyString(), anyString()))
                .thenReturn(new ScanResult(true, ScanResult.PDF, null));
        when(fileStorage.put(anyString(), eq("application/pdf"), any(InputStream.class), anyLong()))
                .thenAnswer(inv -> new StoredFile(inv.getArgument(0), 10L, "0".repeat(64)));
        when(documentRepository.saveAndFlush(any(Document.class)))
                .thenThrow(new DataIntegrityViolationException("insert failed"));

        DocumentService.UploadCommand command = service.prepareUpload(PATIENT, "OTHER", "Scan", null, null, null,
                DocumentFixtures.pdf("scan.pdf"));
        assertThrows(DataIntegrityViolationException.class, () -> service.upload(command));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(authorizationService, rateLimitGuard, fileScanner, fileStorage, transactionManager,
                documentRepository);
        order.verify(authorizationService).requirePatientAccess(PATIENT, PatientPermission.MANAGE_DOCUMENTS);
        order.verify(rateLimitGuard).check(DocumentService.UPLOAD_BUCKET, PATIENT);
        order.verify(fileScanner).scan(any(InputStream.class), anyString(), anyString());
        order.verify(fileStorage).put(key.capture(), eq("application/pdf"), any(InputStream.class), anyLong());
        order.verify(transactionManager).getTransaction(any());
        order.verify(documentRepository).saveAndFlush(any(Document.class));
        assertTrue(key.getValue().matches("documents/2026/11/[0-9a-f-]{36}"), key.getValue());
        verify(transactionManager).rollback(any());
        verify(fileStorage).delete(key.getValue());
        verify(auditService, never()).logClinicalAccess(anyString(), anyString(), anyInt(), anyString());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void uploadOpensNoTransactionWhenTheScanRejectsTheFile() {
        when(referenceRepository.patientExists(PATIENT)).thenReturn(true);
        when(fileScanner.scan(any(InputStream.class), anyString(), anyString()))
                .thenReturn(new ScanResult(false, ScanResult.PDF, "EICAR test signature"));

        DocumentService.UploadCommand command = service.prepareUpload(PATIENT, "OTHER", "Scan", null, null, null,
                DocumentFixtures.pdf("scan.pdf"));
        assertThrows(RuntimeException.class, () -> service.upload(command));

        verify(transactionManager, never()).getTransaction(any());
        verify(fileStorage, never()).put(anyString(), anyString(), any(InputStream.class), anyLong());
        verify(auditService, never()).logClinicalAccess(anyString(), anyString(), isNull(), anyString());
    }
}
