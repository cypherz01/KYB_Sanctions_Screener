package com.cypherz01.kyb.config;

import com.cypherz01.kyb.matching.NameNormaliser;
import com.cypherz01.kyb.sanctions.FtmEntityParser;
import com.cypherz01.kyb.sanctions.OpenSanctionsSource;
import com.cypherz01.kyb.sanctions.SanctionsImportScheduler;
import com.cypherz01.kyb.sanctions.SanctionsImportService;
import com.cypherz01.kyb.sanctions.SanctionsNameIndexer;
import com.cypherz01.kyb.sanctions.SanctionsProperties;
import com.cypherz01.kyb.sanctions.SanctionsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SanctionsProperties.class)
@EnableScheduling
class SanctionsConfig {

    @Bean
    OpenSanctionsSource openSanctionsSource(
            RestClient.Builder builder, SanctionsProperties properties, MeterRegistry meters) {
        return new OpenSanctionsSource(OpenSanctionsSource.buildRestClient(builder, properties), properties, meters);
    }

    @Bean
    FtmEntityParser ftmEntityParser(SanctionsProperties properties) {
        return new FtmEntityParser(properties.schemas(), properties.topics());
    }

    @Bean
    SanctionsRepository sanctionsRepository(
            JdbcTemplate jdbc, PlatformTransactionManager transactions, NameNormaliser normaliser) {
        return new SanctionsRepository(jdbc, new TransactionTemplate(transactions), normaliser);
    }

    /**
     * Background imports use Boot's application task executor. Imports cannot pile up there,
     * because the database allows only one LOADING version at a time.
     */
    @Bean
    SanctionsImportService sanctionsImportService(
            OpenSanctionsSource source,
            FtmEntityParser parser,
            SanctionsRepository repository,
            SanctionsProperties properties,
            ObjectMapper objectMapper,
            Clock clock,
            MeterRegistry meters,
            @Qualifier("applicationTaskExecutor") Executor executor) {
        return new SanctionsImportService(
                source, parser, repository, properties, objectMapper, clock, meters, executor);
    }

    @Bean
    SanctionsImportScheduler sanctionsImportScheduler(
            SanctionsImportService service,
            SanctionsNameIndexer indexer,
            SanctionsProperties properties,
            @Qualifier("applicationTaskExecutor") Executor executor) {
        return new SanctionsImportScheduler(service, indexer, properties, executor);
    }
}
