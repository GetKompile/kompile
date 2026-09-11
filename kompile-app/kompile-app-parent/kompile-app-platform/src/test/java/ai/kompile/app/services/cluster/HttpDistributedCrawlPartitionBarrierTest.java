/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.services.cluster;

import ai.kompile.core.crawl.graph.DistributedCrawlPartitionBarrier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class HttpDistributedCrawlPartitionBarrierTest {

    @Test
    void springSelectsProductionConstructorWithoutAnHttpClientBean() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(HttpDistributedCrawlPartitionBarrier.class);
            context.refresh();
            assertInstanceOf(HttpDistributedCrawlPartitionBarrier.class,
                    context.getBean(DistributedCrawlPartitionBarrier.class));
        }
    }
}
