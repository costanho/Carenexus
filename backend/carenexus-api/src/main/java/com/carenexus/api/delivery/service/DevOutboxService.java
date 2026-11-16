package com.carenexus.api.delivery.service;

import com.carenexus.api.common.config.EnvironmentInfo;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.delivery.DeliveryChannel;
import com.carenexus.api.delivery.config.DeliveryProperties;
import com.carenexus.api.delivery.dto.response.OutboundMessageResponse;
import com.carenexus.api.delivery.model.OutboundMessage;
import com.carenexus.api.delivery.repository.OutboundMessageRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * View of the outbox for installations where messages never leave the machine, so reset and verification links can
 * be opened and read by e2e tests. ADMIN only, and only where it is {@link #isAvailable() available}: while a
 * developer profile (local, docker, test) is active, or outside production while email uses the {@code local}
 * provider (a staging installation without a real email adapter, where this view is the only way to relay
 * verification and password-reset links). On any other installation (production always) the endpoint answers 404
 * as if it did not exist. Read-only: the outbox cannot be edited, resent or deleted here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DevOutboxService {

    public static final int MAX_TO_LENGTH = 254;
    public static final int MAX_TEMPLATE_LENGTH = 80;

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt")
            .and(Sort.by(Sort.Direction.DESC, "messageId"));

    private final OutboundMessageRepository messages;
    private final AuthorizationService authorizationService;
    private final EnvironmentInfo environmentInfo;
    private final DeliveryProperties deliveryProperties;
    private final ObjectMapper objectMapper;

    /**
     * True on a developer profile, or outside production while email uses the {@code local} provider. Never in
     * production, whatever the providers.
     */
    public boolean isAvailable() {
        if (environmentInfo.isDevProfile()) {
            return true;
        }
        return !environmentInfo.isProduction()
                && DeliveryProperties.LOCAL.equals(deliveryProperties.getEmail().providerId());
    }

    /**
     * One page of the outbox, newest first. Blank filters are ignored; filters combine with AND.
     *
     * @param channel  EMAIL, SMS or PUSH (case-insensitive): exact match
     * @param to       case-insensitive "contains" on the recipient address or the recipient system id
     * @param template case-insensitive exact match on the template id
     */
    public PageResponse<OutboundMessageResponse> search(String channel, String to, String template, int page, int size) {
        authorizationService.requireAdmin();
        if (!isAvailable()) {
            throw new ResourceNotFoundException("Resource not found");
        }
        String channelFilter = null;
        if (channel != null && !channel.isBlank()) {
            channelFilter = DeliveryChannel.normalize(channel);
            if (channelFilter == null) {
                throw new BadRequestException("channel: must be EMAIL, SMS or PUSH");
            }
        }
        String toFilter = trimToNull(to);
        if (toFilter != null && toFilter.length() > MAX_TO_LENGTH) {
            throw new BadRequestException("to: size must be between 0 and " + MAX_TO_LENGTH);
        }
        String templateFilter = trimToNull(template);
        if (templateFilter != null && templateFilter.length() > MAX_TEMPLATE_LENGTH) {
            throw new BadRequestException("template: size must be between 0 and " + MAX_TEMPLATE_LENGTH);
        }

        Pageable pageable = PageParams.of(page, size, NEWEST_FIRST);
        Page<OutboundMessage> rows = messages.findAll(filter(channelFilter, toFilter, templateFilter), pageable);
        return PageResponse.of(rows, this::toResponse);
    }

    private static Specification<OutboundMessage> filter(String channel, String to, String template) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (channel != null) {
                predicates.add(cb.equal(root.get("channel"), channel));
            }
            if (to != null) {
                String pattern = "%" + escapeLike(to.toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("recipientAddress")), pattern, '\\'),
                        cb.like(cb.lower(root.get("recipientSystemId")), pattern, '\\')));
            }
            if (template != null) {
                predicates.add(cb.equal(cb.lower(root.get("templateId")), template.toLowerCase(Locale.ROOT)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** {@code %} and {@code _} in the search text are matched literally. */
    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private OutboundMessageResponse toResponse(OutboundMessage message) {
        return new OutboundMessageResponse(
                message.getMessageId(),
                message.getChannel(),
                message.getRecipientSystemId(),
                message.getRecipientAddress(),
                message.getTemplateId(),
                message.getSubject(),
                message.getBody(),
                data(message),
                message.getNotificationId(),
                message.getDedupeKey(),
                message.getStatus(),
                message.getAttempts() == null ? 0 : message.getAttempts(),
                message.getMaxAttempts() == null ? 0 : message.getMaxAttempts(),
                message.getNextAttemptAt(),
                message.getLastError(),
                message.getProvider(),
                message.getProviderMessageId(),
                message.getCreatedAt(),
                message.getSentAt());
    }

    private Map<String, String> data(OutboundMessage message) {
        if (message.getData() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(message.getData(), STRING_MAP);
        } catch (JsonProcessingException e) {
            log.warn("Outbound message {} has a data payload that is not a string map", message.getMessageId());
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
