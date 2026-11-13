package com.carenexus.api.delivery.service;

import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.delivery.OutboundMessageService;
import com.carenexus.api.delivery.dto.response.ChannelStatus;
import com.carenexus.api.delivery.dto.response.NotificationChannelsResponse;
import com.carenexus.api.delivery.email.EmailSender;
import com.carenexus.api.delivery.push.PushSender;
import com.carenexus.api.delivery.sms.SmsSender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Which external delivery channels exist on this installation. The answer describes the installation, not the
 * caller: it is the same for every signed-in user and changes only with the configuration.
 */
@Service
@RequiredArgsConstructor
public class NotificationChannelService {

    private final AuthorizationService authorizationService;
    private final EmailSender emailSender;
    private final SmsSender smsSender;
    private final PushSender pushSender;

    /** Any signed-in user. */
    public NotificationChannelsResponse channels() {
        authorizationService.currentSystemId();
        return new NotificationChannelsResponse(
                status(emailSender.providerName(), emailSender.isConfigured()),
                status(smsSender.providerName(), smsSender.isConfigured()),
                status(pushSender.providerName(), pushSender.isConfigured()));
    }

    private static ChannelStatus status(String providerName, boolean configured) {
        String provider = providerName == null ? "" : providerName.trim().toLowerCase(Locale.ROOT);
        return new ChannelStatus(OutboundMessageService.available(provider, configured), provider);
    }
}
