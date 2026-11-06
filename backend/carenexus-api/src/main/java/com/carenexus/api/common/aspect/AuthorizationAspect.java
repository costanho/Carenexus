package com.carenexus.api.common.aspect;

import com.carenexus.api.common.annotation.RequireAdmin;
import com.carenexus.api.common.annotation.RequireOwnership;
import com.carenexus.api.common.annotation.RequireRole;
import com.carenexus.api.common.config.UserContext;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.service.AuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthorizationAspect {

    private final AuthorizationService authorizationService;
    private final UserContext userContext;

    /**
     * Intercept methods annotated with @RequireRole
     */
    @Around("@annotation(requireRole)")
    public Object checkRoleAuthorization(ProceedingJoinPoint joinPoint, RequireRole requireRole) throws Throwable {
        String[] requiredRoles = requireRole.value();
        String userRole = userContext.getCurrentUserRole();

        boolean authorized = Arrays.asList(requiredRoles).contains(userRole);

        if (!authorized) {
            log.warn("Authorization denied. User role: {}, Required roles: {}", userRole, Arrays.toString(requiredRoles));
            throw new AuthorizationException("Your role (" + userRole + ") is not authorized for this action");
        }

        return joinPoint.proceed();
    }

    /**
     * Intercept methods annotated with @RequireAdmin
     */
    @Around("@annotation(requireAdmin)")
    public Object checkAdminAuthorization(ProceedingJoinPoint joinPoint, RequireAdmin requireAdmin) throws Throwable {
        String userRole = userContext.getCurrentUserRole();
        boolean isAdmin = "ADMIN".equals(userRole);
        boolean isFacilityAdmin = "FACILITY_ADMIN".equals(userRole);

        boolean authorized = isAdmin || (requireAdmin.allowFacilityAdmin() && isFacilityAdmin);

        if (!authorized) {
            log.warn("Admin authorization denied. User role: {}", userRole);
            throw new AuthorizationException("Admin privileges required");
        }

        return joinPoint.proceed();
    }

    /**
     * Intercept methods annotated with @RequireOwnership.
     *
     * The owner parameter is normally a String systemId (e.g. patientSystemId) and is compared with the caller's
     * systemId through AuthorizationService.requireOwnership (which audits denials). A legacy Integer userId
     * parameter is compared with the caller's userId.
     */
    @Around("@annotation(requireOwnership)")
    public Object checkOwnershipAuthorization(ProceedingJoinPoint joinPoint, RequireOwnership requireOwnership) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] parameterNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        Object owner = null;
        boolean ownerParamFound = false;
        Integer resourceId = null;

        for (int i = 0; parameterNames != null && i < parameterNames.length; i++) {
            if (parameterNames[i].equals(requireOwnership.ownerIdParam())) {
                owner = args[i];
                ownerParamFound = true;
            }
            if (!requireOwnership.resourceIdParam().isEmpty()
                    && parameterNames[i].equals(requireOwnership.resourceIdParam())
                    && args[i] instanceof Integer id) {
                resourceId = id;
            }
        }

        if (!ownerParamFound || owner == null) {
            log.error("@RequireOwnership on {} could not find owner parameter '{}'",
                    signature.toShortString(), requireOwnership.ownerIdParam());
            throw new AuthorizationException("You don't have permission to access this resource");
        }

        if (owner instanceof Integer ownerUserId) {
            Integer userId = userContext.getCurrentUserId();
            if (userId == null || !userId.equals(ownerUserId)) {
                log.warn("Ownership check failed for {}", signature.toShortString());
                throw new AuthorizationException(
                        "You don't have permission to access this resource",
                        requireOwnership.resourceType(),
                        resourceId
                );
            }
        } else {
            authorizationService.requireOwnership(String.valueOf(owner), requireOwnership.resourceType(), resourceId);
        }

        return joinPoint.proceed();
    }
}
