package com.albertominetti.orderbook.events;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the {@link MarketEventPublisher} port.
 *
 * <p>The Kafka adapter is created only when {@code orderbook.events.kafka.enabled} is {@code true};
 * otherwise the no-op adapter is. The two conditions are mutually exclusive, so exactly one
 * {@link MarketEventPublisher} bean exists in either case and no bean of the other kind is even
 * built: with events disabled nothing can reach a broker, and nothing connects to one.</p>
 *
 * <p>This is the reporting event stream: append-only facts for backend consumers, independent of
 * the SSE stream channel to the UI served by
 * {@link com.albertominetti.orderbook.service.MarketStreamBroadcaster}, which pushes full-state
 * snapshots to browsers instead.</p>
 */
@Configuration
public class MarketEventConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "orderbook.events.kafka", name = "enabled", havingValue = "true")
    MarketEventPublisher kafkaMarketEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${orderbook.events.kafka.topic:order-events}") String topic) {
        return new KafkaMarketEventPublisher(kafkaTemplate, objectMapper, topic);
    }

    @Bean
    @ConditionalOnProperty(prefix = "orderbook.events.kafka", name = "enabled",
            havingValue = "false", matchIfMissing = true)
    MarketEventPublisher noOpMarketEventPublisher() {
        return new NoOpMarketEventPublisher();
    }
}
