package com.cypherz01.kyb.config;

import com.cypherz01.kyb.api.ApiKeyFilter;
import com.cypherz01.kyb.api.ApiSecurityProperties;
import com.cypherz01.kyb.check.CheckRepository;
import com.cypherz01.kyb.check.CheckService;
import com.cypherz01.kyb.check.ChecksProperties;
import com.cypherz01.kyb.check.SubjectExtractor;
import com.cypherz01.kyb.companieshouse.CompaniesHouseClient;
import com.cypherz01.kyb.fca.FcaRegisterClient;
import com.cypherz01.kyb.matching.MatchingProperties;
import com.cypherz01.kyb.matching.NameNormaliser;
import com.cypherz01.kyb.matching.ScreeningService;
import com.cypherz01.kyb.sanctions.SanctionsAttribution;
import com.cypherz01.kyb.sanctions.SanctionsRepository;
import com.cypherz01.kyb.scoring.RiskScorer;
import com.cypherz01.kyb.scoring.ScoringProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ChecksProperties.class, ScoringProperties.class, ApiSecurityProperties.class})
class CheckConfig {

    @Bean
    RiskScorer riskScorer(ScoringProperties scoring, MatchingProperties matching) {
        // Sanctions bands come from the matcher's config, so POSSIBLE scaling uses the same thresholds.
        return new RiskScorer(
                scoring, matching.bands().possible(), matching.bands().strong());
    }

    @Bean
    SubjectExtractor subjectExtractor(ChecksProperties properties, NameNormaliser normaliser) {
        return new SubjectExtractor(properties, normaliser);
    }

    @Bean
    CheckRepository checkRepository(JdbcTemplate jdbc) {
        return new CheckRepository(jdbc);
    }

    @Bean
    CheckService checkService(
            CompaniesHouseClient companiesHouse,
            FcaRegisterClient fca,
            SanctionsRepository datasets,
            ScreeningService screening,
            NameNormaliser normaliser,
            SubjectExtractor extractor,
            RiskScorer scorer,
            CheckRepository checks,
            ChecksProperties properties,
            ObjectMapper json,
            Clock clock,
            MeterRegistry meters) {
        return new CheckService(
                companiesHouse,
                fca,
                datasets,
                screening,
                normaliser,
                extractor,
                scorer,
                checks,
                properties,
                json,
                clock,
                meters);
    }

    /** Only /api/**: health, metrics and the API docs stay open (local-only deployment). */
    @Bean
    FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(ApiSecurityProperties properties, ObjectMapper json) {
        FilterRegistrationBean<ApiKeyFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyFilter(properties, json));
        registration.addUrlPatterns("/api/*");
        registration.setName("apiKeyFilter");
        return registration;
    }

    @Bean
    OpenAPI openApi(ChecksProperties properties) {
        return new OpenAPI()
                .info(new Info()
                        .title("KYB Sanctions Screener")
                        .version(properties.engineVersion())
                        .description("Automated Know Your Business checks on UK companies: Companies House, the FCA "
                                + "Register and OpenSanctions screening, with scored, explainable decisions. "
                                + "A portfolio project, not a compliance product. Sanctions data from "
                                + SanctionsAttribution.OPENSANCTIONS.name() + " ("
                                + SanctionsAttribution.OPENSANCTIONS.url()
                                + "), " + SanctionsAttribution.OPENSANCTIONS.licence() + ", non-commercial use only.")
                        .license(new License()
                                .name("Data: " + SanctionsAttribution.OPENSANCTIONS.licence())
                                .url(SanctionsAttribution.OPENSANCTIONS.licenceUrl())))
                .components(new Components()
                        .addSecuritySchemes(
                                "ApiKey",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name(ApiSecurityProperties.HEADER)))
                .addSecurityItem(new SecurityRequirement().addList("ApiKey"));
    }
}
