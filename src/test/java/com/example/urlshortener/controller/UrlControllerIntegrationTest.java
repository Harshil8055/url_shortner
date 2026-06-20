package com.example.urlshortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full end-to-end integration tests through the real HTTP layer, real
 * Spring wiring, and the real {@code InMemoryUrlRepository} (no mocks).
 * Covers every flow walked through in HLD §3.
 *
 * <p>Each test uses a unique URL/alias to avoid cross-test interference,
 * since the in-memory repository is a singleton Spring bean shared across
 * all tests in this Spring context.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UrlControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---- HLD §3.1: POST /shorten - no alias, new URL ----

    @Test
    void shortenNewUrlReturns201WithGeneratedCode() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("url", "https://example.com/integration-test-1"));

        mockMvc.perform(post("/shorten").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/integration-test-1"))
                .andExpect(jsonPath("$.shortUrl").value(org.hamcrest.Matchers.containsString("/")));
    }

    // ---- HLD §3.2: POST /shorten - no alias, duplicate URL (idempotent) ----

    @Test
    void shorteningSameUrlTwiceReturnsSameCode() throws Exception {
        String url = "https://example.com/integration-test-duplicate";
        String body = objectMapper.writeValueAsString(Map.of("url", url));

        String firstResponse = mockMvc.perform(post("/shorten").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String firstCode = objectMapper.readTree(firstResponse).get("code").asText();

        String secondResponse = mockMvc.perform(post("/shorten").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String secondCode = objectMapper.readTree(secondResponse).get("code").asText();

        org.assertj.core.api.Assertions.assertThat(secondCode).isEqualTo(firstCode);
    }

    // ---- HLD §3.3: POST /shorten - custom alias, free ----

    @Test
    void shortenWithFreeCustomAliasReturns201WithThatAlias() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("url", "https://example.com/integration-test-alias", "alias", "my-it-alias"));

        mockMvc.perform(post("/shorten").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("my-it-alias"));
    }

    // ---- HLD §3.4: POST /shorten - custom alias, taken ----

    @Test
    void shortenWithTakenAliasReturns409() throws Exception {
        String firstBody = objectMapper.writeValueAsString(
                Map.of("url", "https://example.com/integration-test-conflict-1", "alias", "taken-alias-it"));
        mockMvc.perform(post("/shorten").contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isCreated());

        String secondBody = objectMapper.writeValueAsString(
                Map.of("url", "https://example.com/integration-test-conflict-2", "alias", "taken-alias-it"));
        mockMvc.perform(post("/shorten").contentType(MediaType.APPLICATION_JSON).content(secondBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ALIAS_CONFLICT"));
    }

    // ---- HLD §3.5: GET /{code} - known code ----

    @Test
    void redirectForKnownCodeReturns301WithLocationHeader() throws Exception {
        String targetUrl = "https://example.com/integration-test-redirect-target";
        String shortenBody = objectMapper.writeValueAsString(Map.of("url", targetUrl, "alias", "redirect-it-test"));
        mockMvc.perform(post("/shorten").contentType(MediaType.APPLICATION_JSON).content(shortenBody))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/redirect-it-test"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", equalTo(targetUrl)));
    }

    // ---- HLD §3.6: GET /{code} - unknown code ----

    @Test
    void redirectForUnknownCodeReturns404() throws Exception {
        mockMvc.perform(get("/this-code-was-never-created"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // ---- Validation: invalid URL ----

    @Test
    void shortenWithInvalidUrlReturns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("url", "not a url"));

        mockMvc.perform(post("/shorten").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_URL"));
    }

    // ---- Validation: invalid alias characters ----

    @Test
    void shortenWithInvalidAliasCharactersReturns400() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("url", "https://example.com/integration-test-bad-alias", "alias", "bad alias!"));

        mockMvc.perform(post("/shorten").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ALIAS"));
    }

    // ---- Cross-cutting: a URL can have multiple codes (auto + custom alias) ----

    @Test
    void urlCanBeAccessedViaBothItsAutoCodeAndACustomAlias() throws Exception {
        String url = "https://example.com/integration-test-multi-code";

        String autoBody = objectMapper.writeValueAsString(Map.of("url", url));
        String autoResponse = mockMvc.perform(post("/shorten").contentType(MediaType.APPLICATION_JSON).content(autoBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String autoCode = objectMapper.readTree(autoResponse).get("code").asText();

        String aliasBody = objectMapper.writeValueAsString(Map.of("url", url, "alias", "multi-code-it-alias"));
        mockMvc.perform(post("/shorten").contentType(MediaType.APPLICATION_JSON).content(aliasBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("multi-code-it-alias"));

        // Both codes must independently redirect to the same URL.
        mockMvc.perform(get("/" + autoCode))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", url));
        mockMvc.perform(get("/multi-code-it-alias"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", url));
    }
}
