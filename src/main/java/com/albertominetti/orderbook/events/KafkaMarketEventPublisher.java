package com.albertominetti.orderbook.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka adapter of {@link MarketEventPublisher}.
 *
 * <p>The event is serialized to a JSON string with the Spring-managed {@link ObjectMapper} and sent
 * asynchronously, keyed by the symbol so every event of one instrument lands on the same partition
 * and therefore stays in order.</p>
 *
 * <p>Publishing is purely additive and never throws: the send is asynchronous, a failure only logs
 * a warning, and a serialization error is caught too, so a broken broker can neither fail a request
 * nor slow a matching engine down.</p>
 */
public class KafkaMarketEventPublisher implements MarketEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaMarketEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public KafkaMarketEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                     ObjectMapper objectMapper,
                                     String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void publish(MarketEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, event.symbol(), payload)
                    .whenComplete((result, failure) -> {
                        if (failure != null) {
                            log.warn("Failed to publish {} for {}: {}",
                                    event.type(), event.symbol(), failure.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("Failed to serialize {} for {}: {}",
                    event.type(), event.symbol(), e.getMessage());
        }
    }
}