package com.auth.server.aspect;

import com.auth.server.annotation.RequireAdmin;
import com.auth.server.annotation.RequireOwnership;
import com.auth.server.annotation.RequireRole;
import com.auth.server.config.UserContext;
import com.auth.server.exception.AuthorizationException;
import com.auth.server.service.AuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
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
     * Intercept methods annotated with @RequireOwnership
     */
    @Around("@annotation(requireOwnership)")
    public Object checkOwnershipAuthorization(ProceedingJoinPoint joinPoint, RequireOwnership requireOwnership) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String[] parameterNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        // Find the owner ID from method parameters
        Integer ownerId = null;
        Integer resourceId = null;

        for (int i = 0; i < parameterNames.length; i++) {
            if (parameterNames[i].equals(requireOwnership.ownerIdParam())) {
                ownerId = (Integer) args[i];
            }
            if (!requireOwnership.resourceIdParam().isEmpty() && parameterNames[i].equals(requireOwnership.resourceIdParam())) {
                resourceId = (Integer) args[i];
            }
        }

        if (ownerId == null) {
            log.error("Could not find owner ID parameter: {}", requireOwnership.ownerIdParam());
            throw new AuthorizationException("Authorization check failed: owner ID not found");
        }

        Integer userId = userContext.getCurrentUserId();
        if (!userId.equals(ownerId)) {
            log.warn("Ownership check failed. User: {}, Owner: {}", userId, ownerId);
            throw new AuthorizationException(
                    "You don't have permission to access this resource",
                    requireOwnership.resourceType(),
                    resourceId
            );
        }

        return joinPoint.proceed();
    }
}
