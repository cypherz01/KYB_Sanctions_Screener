package com.cypherz01.kyb.config;

import com.cypherz01.kyb.matching.CandidateRetriever;
import com.cypherz01.kyb.matching.MatchingProperties;
import com.cypherz01.kyb.matching.NameMatcher;
import com.cypherz01.kyb.matching.NameNormaliser;
import com.cypherz01.kyb.matching.ScreeningService;
import com.cypherz01.kyb.sanctions.SanctionsNameIndexer;
import com.cypherz01.kyb.sanctions.SanctionsRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MatchingProperties.class)
class MatchingConfig {

    @Bean
    NameNormaliser nameNormaliser(MatchingProperties p) {
        return new NameNormaliser(p.honorifics(), p.legalForms(), p.leadingLegalForms());
    }

    @Bean
    NameMatcher nameMatcher(MatchingProperties p) {
        return new NameMatcher(p);
    }

    @Bean
    CandidateRetriever candidateRetriever(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            NameNormaliser normaliser,
            MatchingProperties p) {
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        return new CandidateRetriever(jdbc, readOnly, normaliser, p.retrieval());
    }

    @Bean
    ScreeningService screeningService(
            SanctionsRepository datasets,
            CandidateRetriever retriever,
            NameNormaliser normaliser,
            NameMatcher matcher,
            MatchingProperties p,
            MeterRegistry meters) {
        return new ScreeningService(datasets, retriever, normaliser, matcher, p, meters);
    }

    @Bean
    SanctionsNameIndexer sanctionsNameIndexer(
            JdbcTemplate jdbc, PlatformTransactionManager transactions, NameNormaliser normaliser) {
        return new SanctionsNameIndexer(jdbc, new TransactionTemplate(transactions), normaliser);
    }
}
