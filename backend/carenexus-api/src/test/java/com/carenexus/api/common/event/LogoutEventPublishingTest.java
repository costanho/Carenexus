package com.carenexus.api.common.event;

import com.carenexus.api.auth.config.JwtUtil;
import com.carenexus.api.auth.model.RefreshToken;
import com.carenexus.api.auth.repository.RefreshTokenRepository;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.auth.service.LoginAttemptThrottle;
import com.carenexus.api.auth.service.SystemIdGenerator;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.core.repository.CaregiverRepository;
import com.carenexus.api.core.repository.DependentAccessRepository;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.repository.PatientRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AuthService publishes UserLoggedOutEvent on logout (consumed by calls to end open participation).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class LogoutEventPublishingTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtUtil jwtUtil;
    @Mock private AuditService auditService;
    @Mock private PatientRepository patientRepository;
    @Mock private DoctorRepository doctorRepository;
    @Mock private CaregiverRepository caregiverRepository;
    @Mock private DependentAccessRepository dependentAccessRepository;
    @Mock private SystemIdGenerator systemIdGenerator;
    @Mock private LoginAttemptThrottle loginAttemptThrottle;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AuthService authService;

    @Test
    public void logoutEverywhere_publishesEvent() {
        when(refreshTokenRepository.findBySystemIdAndIsRevokedFalse("CGV-00001")).thenReturn(List.of());

        authService.logout("CGV-00001", "127.0.0.1");

        verify(eventPublisher).publishEvent((Object) new UserLoggedOutEvent("CGV-00001"));
    }

    @Test
    public void logoutOneSession_publishesEventForTheTokenOwner() {
        RefreshToken token = new RefreshToken();
        token.setSystemId("PAT-00001");
        token.setIsRevoked(false);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        authService.logoutRefreshToken("raw-refresh-token", "127.0.0.1");

        verify(eventPublisher).publishEvent((Object) new UserLoggedOutEvent("PAT-00001"));
    }

    @Test
    public void logoutWithUnknownToken_publishesNothing() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        authService.logoutRefreshToken("unknown", "127.0.0.1");
        authService.logoutRefreshToken(" ", "127.0.0.1");

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }
}
