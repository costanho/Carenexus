package com.carenexus.api.common.config;

import com.carenexus.api.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * pom.xml pins the Swagger UI webjar (swagger-ui.version) above the one springdoc was built with, and
 * application.yml passes that version to springdoc (springdoc.swagger-ui.version). When the two disagree springdoc
 * looks for the files under the wrong webjar version and every Swagger UI file answers 404.
 */
@TestPropertySource(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
class SwaggerUiWebjarTest extends BaseIntegrationTest {

    @Test
    void swaggerUi_isServedFromThePinnedWebjar() throws Exception {
        MvcResult entry = mockMvc.perform(get("/swagger-ui.html")).andReturn();
        assertEquals(302, entry.getResponse().getStatus());
        assertEquals("/swagger-ui/index.html", entry.getResponse().getRedirectedUrl());

        MvcResult page = mockMvc.perform(get("/swagger-ui/index.html")).andReturn();
        assertEquals(200, page.getResponse().getStatus());
        assertTrue(page.getResponse().getContentAsString().contains("swagger-ui"));

        MvcResult bundle = mockMvc.perform(get("/swagger-ui/swagger-ui-bundle.js")).andReturn();
        assertEquals(200, bundle.getResponse().getStatus());

        MvcResult config = mockMvc.perform(get("/v3/api-docs/swagger-config")).andReturn();
        assertEquals(200, config.getResponse().getStatus());
        assertTrue(config.getResponse().getContentAsString().contains("\"url\":\"/v3/api-docs\""));
    }
}
