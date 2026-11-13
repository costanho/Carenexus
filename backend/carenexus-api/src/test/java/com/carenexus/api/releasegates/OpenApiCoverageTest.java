package com.carenexus.api.releasegates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Release gate for the API documentation (CONTRACTS 3.7). The context runs with springdoc switched on, loads the
 * generated document from {@code /v3/api-docs} and checks every operation in it:
 * <ul>
 *   <li>a summary (imperative, under 80 characters) and a description of who may call it;</li>
 *   <li>at least one tag, and every tag is a declared {@code @Tag} with a description (springdoc otherwise invents a
 *       tag from the controller's class name);</li>
 *   <li>its success answer (at least one 2xx response; springdoc drops the inferred 200 as soon as any
 *       {@code @ApiResponse}, {@code @StandardErrors} included, is declared without one);</li>
 *   <li>every 4xx/5xx response it documents uses the shared {@code ErrorResponse} schema, and every operation that
 *       needs a token documents the 401 answer;</li>
 *   <li>public operations (the SecurityConfig permit list) declare no security requirement, every other operation
 *       inherits the global bearer requirement;</li>
 *   <li>a handler method marked {@code @Deprecated} is a deprecated operation, and a deprecated operation is a
 *       {@code @Deprecated} handler method;</li>
 *   <li>every {@code /api} handler mapping is in the document (none is silently missing).</li>
 * </ul>
 * Each check collects every violation before failing, so one run lists all the gaps.
 *
 * The static helpers ({@link #loadApiDocs}, {@link #operations}, {@link #isPublic}) are shared with
 * {@link SecurityRouteReleaseGateTest}, which walks the same document.
 */
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
public class OpenApiCoverageTest extends CommittedIntegrationTest {

    static final String ERROR_SCHEMA_REF = "#/components/schemas/ErrorResponse";
    static final int MAX_SUMMARY_LENGTH = 80;

    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final Pattern PATH_VARIABLE_REGEX = Pattern.compile("\\{([^}:]+):[^}]*}");
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

    /**
     * Routes SecurityConfig lets through without a token (method, Ant pattern). Mirrors CONTRACTS 3.8; the
     * security gate proves the filter chain agrees with this list in both directions.
     */
    static final List<String[]> PUBLIC_ROUTES = List.of(
            new String[]{"*", "/api/auth/login"},
            new String[]{"*", "/api/auth/refresh"},
            new String[]{"*", "/api/auth/register"},
            new String[]{"*", "/api/auth/logout"},
            new String[]{"POST", "/api/auth/password/forgot"},
            new String[]{"POST", "/api/auth/password/reset"},
            new String[]{"POST", "/api/auth/email/verify"},
            new String[]{"POST", "/api/auth/email/resend"},
            new String[]{"POST", "/api/auth/email-change/confirm"},
            new String[]{"POST", "/api/auth/mfa/**"},
            new String[]{"GET", "/api/legal/**"},
            new String[]{"POST", "/api/integrations/livekit/webhook"},
            new String[]{"POST", "/api/integrations/payments/*/webhook"});

    /**
     * Error responses that deliberately do not use the ErrorResponse envelope (method + path).
     * The LiveKit webhook answers its caller (LiveKit, never a user client) with a bare {@code {"message": ...}}
     * body; its unit test reads that map, so the shape is kept and documented as it is.
     */
    static final Set<String> NON_ENVELOPE_ERRORS = Set.of("POST /api/integrations/livekit/webhook");

    /**
     * Public routes that also read a bearer token when one is sent, so they keep the global bearer requirement in
     * the document: POST /api/auth/logout ends every session of the caller when the body has no refresh token.
     */
    static final Set<String> OPTIONAL_TOKEN_ROUTES = Set.of("POST /api/auth/logout");

    /** One documented operation. */
    record Op(String method, String path, JsonNode node) {
        String key() {
            return method + " " + path;
        }
    }

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private JsonNode docs;

    @BeforeEach
    void loadDocs() throws Exception {
        docs = loadApiDocs(mockMvc, objectMapper);
    }

    // ==================== SHARED HELPERS ====================

    /** The generated OpenAPI document (public while springdoc is enabled). */
    static JsonNode loadApiDocs(MockMvc mockMvc, ObjectMapper objectMapper) throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs")).andReturn();
        assertEquals(200, result.getResponse().getStatus(), "GET /v3/api-docs must answer 200 with springdoc enabled");
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Every operation of the document, method upper case, in document order. */
    static List<Op> operations(JsonNode docs) {
        List<Op> ops = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> paths = docs.path("paths").fields();
        while (paths.hasNext()) {
            Map.Entry<String, JsonNode> path = paths.next();
            Iterator<Map.Entry<String, JsonNode>> methods = path.getValue().fields();
            while (methods.hasNext()) {
                Map.Entry<String, JsonNode> method = methods.next();
                if (HTTP_METHODS.contains(method.getKey())) {
                    ops.add(new Op(method.getKey().toUpperCase(Locale.ROOT), path.getKey(), method.getValue()));
                }
            }
        }
        return ops;
    }

    /** True when SecurityConfig lets {@code method path} through without a token. */
    static boolean isPublic(String method, String path) {
        String concrete = concretePath(path);
        for (String[] route : PUBLIC_ROUTES) {
            if (("*".equals(route[0]) || route[0].equals(method)) && MATCHER.match(route[1], concrete)) {
                return true;
            }
        }
        return false;
    }

    /** A request path for a documented path template: every variable becomes a value that fits its usual type. */
    static String concretePath(String template) {
        return template.replaceAll("\\{[^}]*[sS]ystemId}", "PAT-00001").replaceAll("\\{[^}]+}", "1");
    }

    private static String normalize(String pattern) {
        return PATH_VARIABLE_REGEX.matcher(pattern).replaceAll("{$1}");
    }

    private static void failOnViolations(String what, Set<String> violations) {
        if (!violations.isEmpty()) {
            fail(violations.size() + " " + what + ":\n  " + String.join("\n  ", violations));
        }
    }

    // ==================== CHECKS ====================

    @Test
    void documentIsGeneratedWithTheSharedErrorSchema() {
        assertFalse(operations(docs).isEmpty(), "the document lists no operation");
        assertTrue(docs.path("components").path("schemas").has("ErrorResponse"),
                "components.schemas.ErrorResponse is missing");
        assertTrue(docs.path("components").path("securitySchemes").has("bearer-jwt"),
                "the bearer security scheme is missing");
    }

    @Test
    void everyOperationHasASummaryAndADescription() {
        Set<String> violations = new TreeSet<>();
        for (Op op : operations(docs)) {
            String summary = op.node().path("summary").asText("").trim();
            if (summary.isEmpty()) {
                violations.add(op.key() + ": no summary");
            } else if (summary.length() > MAX_SUMMARY_LENGTH) {
                violations.add(op.key() + ": summary longer than " + MAX_SUMMARY_LENGTH + " characters");
            }
            if (op.node().path("description").asText("").isBlank()) {
                violations.add(op.key() + ": no description (who may call, side effects)");
            }
        }
        failOnViolations("operations without a usable summary or description", violations);
    }

    @Test
    void everyOperationDocumentsItsSuccessResponse() {
        Set<String> violations = new TreeSet<>();
        for (Op op : operations(docs)) {
            boolean success = false;
            Iterator<String> codes = op.node().path("responses").fieldNames();
            while (codes.hasNext()) {
                success |= codes.next().startsWith("2");
            }
            if (!success) {
                violations.add(op.key() + ": no 2xx response");
            }
        }
        failOnViolations("operations without a documented success response", violations);
    }

    @Test
    void everyOperationHasADeclaredTag() {
        Set<String> declared = new LinkedHashSet<>();
        Set<String> violations = new TreeSet<>();
        for (JsonNode tag : docs.path("tags")) {
            declared.add(tag.path("name").asText());
            if (tag.path("description").asText("").isBlank()) {
                violations.add("tag '" + tag.path("name").asText() + "' has no description");
            }
        }
        for (Op op : operations(docs)) {
            JsonNode tags = op.node().path("tags");
            if (!tags.isArray() || tags.isEmpty()) {
                violations.add(op.key() + ": no tag");
                continue;
            }
            for (JsonNode tag : tags) {
                if (!declared.contains(tag.asText())) {
                    violations.add(op.key() + ": tag '" + tag.asText() + "' is not a declared @Tag");
                }
            }
        }
        failOnViolations("tag problems", violations);
    }

    @Test
    void errorResponsesUseTheErrorEnvelope_andProtectedOperationsDocument401() {
        Set<String> violations = new TreeSet<>();
        for (Op op : operations(docs)) {
            JsonNode responses = op.node().path("responses");
            if (!isPublic(op.method(), op.path()) && !responses.has("401")) {
                violations.add(op.key() + ": needs a token but does not document 401");
            }
            if (NON_ENVELOPE_ERRORS.contains(op.key())) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> codes = responses.fields();
            while (codes.hasNext()) {
                Map.Entry<String, JsonNode> response = codes.next();
                String code = response.getKey();
                if (!(code.startsWith("4") || code.startsWith("5"))) {
                    continue;
                }
                JsonNode content = response.getValue().path("content");
                if (!content.isObject() || content.isEmpty()) {
                    violations.add(op.key() + " " + code + ": no ErrorResponse content");
                    continue;
                }
                Iterator<Map.Entry<String, JsonNode>> mediaTypes = content.fields();
                while (mediaTypes.hasNext()) {
                    Map.Entry<String, JsonNode> media = mediaTypes.next();
                    String ref = media.getValue().path("schema").path("$ref").asText("");
                    if (!ERROR_SCHEMA_REF.equals(ref)) {
                        violations.add(op.key() + " " + code + " (" + media.getKey() + "): schema is '"
                                + (ref.isEmpty() ? media.getValue().path("schema") : ref) + "', not ErrorResponse");
                    }
                }
            }
        }
        failOnViolations("error-response documentation problems", violations);
    }

    @Test
    void publicOperationsDeclareNoSecurity_andTheOthersKeepTheBearerScheme() {
        assertTrue(docs.path("security").toString().contains("bearer-jwt"),
                "the bearer scheme must be the global security requirement");
        Set<String> violations = new TreeSet<>();
        for (Op op : operations(docs)) {
            JsonNode security = op.node().get("security");
            boolean declaresNone = security != null && security.isArray() && security.isEmpty();
            if (isPublic(op.method(), op.path()) && !declaresNone && !OPTIONAL_TOKEN_ROUTES.contains(op.key())) {
                violations.add(op.key() + ": public route without @SecurityRequirements (empty security)");
            }
            if (!isPublic(op.method(), op.path()) && declaresNone) {
                violations.add(op.key() + ": needs a token but declares no security requirement");
            }
        }
        failOnViolations("security declaration problems", violations);
    }

    @Test
    void deprecatedHandlersAreDeprecatedOperations_andViceVersa() {
        Map<String, Op> byKey = new java.util.HashMap<>();
        for (Op op : operations(docs)) {
            byKey.put(op.key(), op);
        }
        Set<String> violations = new TreeSet<>();
        Set<String> deprecatedHandlers = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            boolean deprecated = handler.getMethod().isAnnotationPresent(Deprecated.class)
                    || handler.getBeanType().isAnnotationPresent(Deprecated.class);
            for (String key : keysOf(entry.getKey())) {
                if (deprecated) {
                    deprecatedHandlers.add(key);
                    Op op = byKey.get(key);
                    if (op != null && !op.node().path("deprecated").asBoolean(false)) {
                        violations.add(key + ": @Deprecated handler is not a deprecated operation");
                    }
                }
            }
        }
        for (Op op : byKey.values()) {
            if (op.node().path("deprecated").asBoolean(false) && !deprecatedHandlers.contains(op.key())) {
                violations.add(op.key() + ": deprecated operation whose handler method is not @Deprecated");
            }
        }
        failOnViolations("deprecation flag problems", violations);
    }

    @Test
    void everyApiHandlerMappingIsDocumented() {
        Set<String> documented = new java.util.HashSet<>();
        for (Op op : operations(docs)) {
            documented.add(op.key());
        }
        Set<String> violations = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (handler.getMethod().isAnnotationPresent(Hidden.class)
                    || handler.getBeanType().isAnnotationPresent(Hidden.class)) {
                continue;
            }
            for (String key : keysOf(entry.getKey())) {
                if (key.contains(" /api/") && !documented.contains(key)) {
                    violations.add(key + " (" + handler.getBeanType().getSimpleName() + "."
                            + handler.getMethod().getName() + ") is not in /v3/api-docs");
                }
            }
        }
        failOnViolations("undocumented handler mappings", violations);
    }

    /** "METHOD /path" keys of a handler mapping; a mapping without methods counts for every documented method. */
    private static Set<String> keysOf(RequestMappingInfo info) {
        Set<String> keys = new TreeSet<>();
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        Set<String> names = new TreeSet<>();
        if (methods.isEmpty()) {
            for (String m : HTTP_METHODS) {
                names.add(m.toUpperCase(Locale.ROOT));
            }
        } else {
            methods.forEach(m -> names.add(m.name()));
        }
        names.removeIf(m -> HttpMethod.valueOf(m) == HttpMethod.HEAD || HttpMethod.valueOf(m) == HttpMethod.OPTIONS);
        for (String pattern : info.getPatternValues()) {
            for (String method : names) {
                keys.add(method + " " + normalize(pattern));
            }
        }
        return keys;
    }
}
