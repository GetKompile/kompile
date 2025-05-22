package ai.kompile.app.pgml.indexer.config;

import ai.kompile.app.pgml.indexer.PgmlIndexerServiceImpl;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.loaders.DocumentLoader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConditionalOnClass({IndexerService.class, PgmlIndexerProperties.class})
@EnableConfigurationProperties(PgmlIndexerProperties.class)
@ConditionalOnProperty(prefix = "kompile.indexer.pgml", name = "enabled", havingValue = "true")
public class PgmlIndexerAutoConfiguration {

    private List<DocumentLoader> loaders;
    @Bean("pgmlIndexerService")
    public IndexerService pgmlIndexerService(PgmlIndexerProperties pgmlIndexerProperties,
                                             ApplicationContext applicationContext) {
        return new PgmlIndexerServiceImpl(pgmlIndexerProperties, applicationContext,loaders);
    }
}