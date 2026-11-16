package com.carenexus.api.common.controller;

import com.carenexus.api.admin.audit.AuditQueryService;
import com.carenexus.api.admin.audit.dto.AuditLogResponse;
import com.carenexus.api.common.annotation.RequireAdmin;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only audit log access for administrators (ADMIN only; audit rows are never changed through the API).
 * Every successful read is itself audited (READ / AUDIT_LOG). Thin: the rules live in {@link AuditQueryService}.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Admin: audit log", description = "Search and read the audit log; every read is itself audited")
public class AuditAdminController {

    private final AuditQueryService auditQueryService;

    @GetMapping("/audit-logs")
    @RequireAdmin(allowFacilityAdmin = false)
    @Operation(summary = "Search the audit log",
            description = "ADMIN only. All filters optional and combined with AND; newest first. actor and patient "
                    + "are exact system ids (systemId and patientSystemId are accepted as older names); from and to "
                    + "take YYYY-MM-DD or YYYY-MM-DDTHH:mm[:ss] in clinic time, a date-only 'to' includes the whole "
                    + "day. Default page size 50. Audited READ AUDIT_LOG.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of audit log rows")
    public ResponseEntity<PageResponse<AuditLogResponse>> getAuditLogs(
            @Parameter(description = "System id of the actor") @RequestParam(required = false) String actor,
            @Parameter(description = "Patient whose data was touched") @RequestParam(required = false) String patient,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @Parameter(description = "SUCCESS or FAILURE") @RequestParam(required = false) String status,
            @Parameter(description = "Lower bound, inclusive") @RequestParam(required = false) String from,
            @Parameter(description = "Upper bound, inclusive") @RequestParam(required = false) String to,
            @Parameter(description = "Older name of actor; used only when actor is absent", deprecated = true)
            @RequestParam(required = false) String systemId,
            @Parameter(description = "Older name of patient; used only when patient is absent", deprecated = true)
            @RequestParam(required = false) String patientSystemId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        AuditQueryService.Criteria criteria = new AuditQueryService.Criteria(
                firstPresent(actor, systemId), firstPresent(patient, patientSystemId), action, resourceType, status,
                from, to);
        return ResponseEntity.ok(auditQueryService.search(criteria, page, size));
    }

    @GetMapping("/audit-logs/{auditId}")
    @RequireAdmin(allowFacilityAdmin = false)
    @Operation(summary = "Get one audit log entry", description = "ADMIN only. Audited READ AUDIT_LOG.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The audit log")
    public ResponseEntity<AuditLogResponse> getAuditLogEntry(@PathVariable Long auditId) {
        return ResponseEntity.ok(auditQueryService.get(auditId));
    }

    private static String firstPresent(String preferred, String alias) {
        return preferred != null && !preferred.isBlank() ? preferred : alias;
    }
}
