package com.carenexus.api.account.datarights.service;

import com.carenexus.api.account.datarights.model.AccountRequest;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationLinks;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import com.carenexus.api.delivery.OutboundMessageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Emails (through the delivery outbox) and in-app notifications (through {@link NotificationPublisher}) of the
 * data-rights flows. Everything joins the caller's transaction, so a rolled-back request sends nothing. Texts are
 * generic and never contain clinical values.
 */
@Component
public class DataRightsMessages {

    static final String EXPORT_READY_TITLE = "Your data export is ready";
    static final String EXPORT_READY_MESSAGE = "Your data export is ready to download. It is available for 7 days.";
    static final String DELETION_REQUEST_TITLE = "Account deletion request";
    static final String DELETION_REJECTED_TITLE = "Account deletion request declined";
    static final String DELETION_REJECTED_MESSAGE = "Your request to delete your account was declined. "
            + "Open Privacy and data to see the reason.";

    private static final DateTimeFormatter EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH);

    private final OutboundMessageService outboundMessageService;
    private final NotificationPublisher notificationPublisher;
    private final String webBaseUrl;

    public DataRightsMessages(OutboundMessageService outboundMessageService,
                              NotificationPublisher notificationPublisher,
                              @Value("${carenexus.web.base-url:http://localhost:8081}") String webBaseUrl) {
        this.outboundMessageService = outboundMessageService;
        this.notificationPublisher = notificationPublisher;
        this.webBaseUrl = stripTrailingSlash(webBaseUrl);
    }

    /** Email {@code account-export-ready} (when the account has an email) and the SYSTEM notification. */
    public void exportReady(User user, AccountRequest export, ZoneId clinicZone) {
        if (hasText(user.getEmail())) {
            Map<String, String> model = new LinkedHashMap<>();
            model.put("firstName", user.getFirstName());
            model.put("expiresAt", export.getExpiresAt().format(EXPIRY_FORMAT) + " (" + clinicZone.getId() + ")");
            model.put("link", securityLink(user.getRole()));
            outboundMessageService.queueEmail(user.getEmail(), user.getSystemId(),
                    DataRightsConstants.TEMPLATE_EXPORT_READY, model,
                    DataRightsConstants.TEMPLATE_EXPORT_READY + ":" + export.getRequestId());
        }
        notificationPublisher.publish(NotificationCommand.builder()
                .recipientSystemId(user.getSystemId())
                .type(NotificationType.SYSTEM)
                .category(NotificationCategory.SYSTEM)
                .priority(NotificationPriority.NORMAL)
                .title(EXPORT_READY_TITLE)
                .message(EXPORT_READY_MESSAGE)
                .target(NotificationTarget.ACCOUNT_SECURITY)
                .actorSystemId(user.getSystemId())
                .dedupeKey("DATA_EXPORT_READY:" + export.getRequestId())
                .expiresAt(export.getExpiresAt())
                .build());
    }

    /** Email {@code account-closed} after a self-service closure (when the account has an email). */
    public void accountClosed(User user, AccountRequest closure) {
        if (!hasText(user.getEmail())) {
            return;
        }
        outboundMessageService.queueEmail(user.getEmail(), user.getSystemId(), DataRightsConstants.TEMPLATE_CLOSED,
                Map.of("firstName", nullToEmpty(user.getFirstName())),
                DataRightsConstants.TEMPLATE_CLOSED + ":" + closure.getRequestId());
    }

    /**
     * Email {@code account-closed-deletion} to the address the account has before it is anonymised.
     *
     * @param formerEmail   the address read before anonymisation (skipped when null)
     * @param formerFirstName the first name read before anonymisation
     */
    public void accountDeleted(String systemId, String formerEmail, String formerFirstName, AccountRequest request) {
        if (!hasText(formerEmail)) {
            return;
        }
        outboundMessageService.queueEmail(formerEmail, systemId, DataRightsConstants.TEMPLATE_CLOSED_DELETION,
                Map.of("firstName", nullToEmpty(formerFirstName)),
                DataRightsConstants.TEMPLATE_CLOSED_DELETION + ":" + request.getRequestId());
    }

    /** SYSTEM notification of a new deletion request to every given administrator. */
    public void deletionRequested(AccountRequest request, List<String> adminSystemIds) {
        for (String adminSystemId : adminSystemIds) {
            notificationPublisher.publish(NotificationCommand.builder()
                    .recipientSystemId(adminSystemId)
                    .type(NotificationType.SYSTEM)
                    .category(NotificationCategory.SYSTEM)
                    .priority(NotificationPriority.NORMAL)
                    .title(DELETION_REQUEST_TITLE)
                    .message("A user (" + request.getSystemId() + ") asked for their account to be deleted.")
                    .target(NotificationTarget.ADMIN_ACCOUNT_REQUESTS)
                    .param("id", String.valueOf(request.getRequestId()))
                    .actorSystemId(request.getSystemId())
                    .dedupeKey("ACCOUNT_DELETION_REQUEST:" + request.getRequestId())
                    .build());
        }
    }

    /** SYSTEM notification to the requester that an administrator declined the deletion request. */
    public void deletionRejected(AccountRequest request, String adminSystemId) {
        notificationPublisher.publish(NotificationCommand.builder()
                .recipientSystemId(request.getSystemId())
                .type(NotificationType.SYSTEM)
                .category(NotificationCategory.SYSTEM)
                .priority(NotificationPriority.NORMAL)
                .title(DELETION_REJECTED_TITLE)
                .message(DELETION_REJECTED_MESSAGE)
                .target(NotificationTarget.ACCOUNT_SECURITY)
                .actorSystemId(adminSystemId)
                .dedupeKey("ACCOUNT_DELETION_REJECTED:" + request.getRequestId())
                .build());
    }

    /** Absolute web link to the account-security screen of the role (base URL + the ACCOUNT_SECURITY deep link). */
    String securityLink(String role) {
        String path = NotificationLinks.url(role, NotificationTarget.ACCOUNT_SECURITY, null, null);
        return path == null ? webBaseUrl : webBaseUrl + path;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8081";
        }
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
