package com.cypherz01.kyb.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires {@code X-Api-Key} on {@code /api/**} (spec §7). Without a configured key it refuses every
 * call (fail closed), so a missing environment variable can never leave the API open. The key is
 * compared in constant time and never logged.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    private final byte[] expected;
    private final ObjectMapper json;

    public ApiKeyFilter(ApiSecurityProperties properties, ObjectMapper json) {
        String key = properties.apiKey();
        this.expected = key == null || key.isBlank() ? null : key.getBytes(StandardCharsets.UTF_8);
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (expected == null) {
            reject(
                    request,
                    response,
                    "API key not configured on the server (set KYB_API_KEY), so every request is refused whatever "
                            + ApiSecurityProperties.HEADER + " it sends");
            return;
        }
        String presented = request.getHeader(ApiSecurityProperties.HEADER);
        if (presented == null || presented.isBlank()) {
            reject(request, response, "Missing " + ApiSecurityProperties.HEADER + " header");
            return;
        }
        if (!MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
            reject(request, response, "Invalid " + ApiSecurityProperties.HEADER);
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String detail) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", "Unauthorized");
        problem.put("status", HttpServletResponse.SC_UNAUTHORIZED);
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("WWW-Authenticate", "ApiKey header=\"" + ApiSecurityProperties.HEADER + "\"");
        json.writeValue(response.getOutputStream(), problem);
    }
}
