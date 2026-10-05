package com.albertominetti.orderbook.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of the wiring of the {@link MarketEventPublisher} port.
 *
 * <p>No broker is started: the Kafka adapter is built with a mocked {@link KafkaTemplate}, which is
 * exactly why it can be asserted to exist (or not) without a connection.</p>
 */
class MarketEventConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MarketEventConfiguration.class);

    @Test
    @DisplayName("Without the property the single publisher is the no-op one")
    void defaultsToTheNoOpPublisher() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(MarketEventPublisher.class);
            assertThat(context).getBean(MarketEventPublisher.class)
                    .isInstanceOf(NoOpMarketEventPublisher.class);
        });
    }

    @Test
    @DisplayName("With the property explicitly false the publisher is still the no-op one")
    void disabledPropertyKeepsTheNoOpPublisher() {
        runner.withPropertyValues("orderbook.events.kafka.enabled=false")
                .run(context -> assertThat(context).getBean(MarketEventPublisher.class)
                        .isInstanceOf(NoOpMarketEventPublisher.class));
    }

    @Test
    @DisplayName("With the property true the single publisher is the Kafka one")
    void enabledPropertyCreatesTheKafkaPublisher() {
        runner.withPropertyValues("orderbook.events.kafka.enabled=true")
                .withUserConfiguration(StubBeans.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(MarketEventPublisher.class);
                    assertThat(context).getBean(MarketEventPublisher.class)
                            .isInstanceOf(KafkaMarketEventPublisher.class);
                });
    }

    @Test
    @DisplayName("The topic is configurable and does not affect which publisher is created")
    void topicIsConfigurable() {
        runner.withPropertyValues("orderbook.events.kafka.enabled=true",
                        "orderbook.events.kafka.topic=my-events")
                .withUserConfiguration(StubBeans.class)
                .run(context -> assertThat(context).hasSingleBean(KafkaMarketEventPublisher.class));

        runner.withPropertyValues("orderbook.events.kafka.enabled=true")
                .withUserConfiguration(StubBeans.class)
                .run(context -> assertThat(context).hasSingleBean(KafkaMarketEventPublisher.class));
    }

    /** The two beans the Kafka adapter needs, mocked: nothing is ever sent through them. */
    @Configuration(proxyBeanMethods = false)
    static class StubBeans {

        @Bean
        KafkaTemplate<String, String> kafkaTemplate() {
            return Mockito.mock(KafkaTemplate.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return Mockito.mock(ObjectMapper.class);
        }
    }
}