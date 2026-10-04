package com.albertominetti.orderbook.engine;

import com.albertominetti.orderbook.domain.OrderStatus;
import com.albertominetti.orderbook.domain.OrderType;
import com.albertominetti.orderbook.domain.PriceLevel;
import com.albertominetti.orderbook.domain.Side;
import com.albertominetti.orderbook.domain.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boundary conditions of the matching engine: the smallest and the largest quantities, price
 * levels written with a different scale, price improvement across levels and the exact book state
 * left behind by an aggressive order.
 */
class MatchingEngineBoundaryTest {

    /** Frozen clock so timestamps are deterministic. */
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine("BOUND", FIXED_CLOCK);
    }

    // ------------------------------------------------------------------ helpers

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private MatchResult buy(String price, String quantity) {
        return engine.submit(Side.BUY, OrderType.LIMIT, bd(price), bd(quantity));
    }

    private MatchResult sell(String price, String quantity) {
        return engine.submit(Side.SELL, OrderType.LIMIT, bd(price), bd(quantity));
    }

    private MatchResult marketBuy(String quantity) {
        return engine.submit(Side.BUY, OrderType.MARKET, null, bd(quantity));
    }

    /** Sum of the quantities of the given trades, computed without any rounding. */
    private static BigDecimal totalQuantity(List<Trade> trades) {
        return trades.stream().map(Trade::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ------------------------------------------------------------------ smallest quantities

    @Nested
    @DisplayName("the smallest quantity of the 8 decimal scale trades exactly")
    class SmallestQuantities {

        @Test
        @DisplayName("A sell of 0.00000003 matched by a buy of 0.00000003 trades once and empties the book")
        void eightDecimalQuantitiesMatchExactly() {
            MatchResult resting = sell("100.00000000", "0.00000003");

            MatchResult aggressor = buy("100.00000000", "0.00000003");

            assertThat(aggressor.trades()).hasSize(1);
            assertThat(aggressor.trades().get(0).quantity()).isEqualByComparingTo("0.00000003");
            // The smallest quantity is traded to the last decimal place, never rounded away.
            assertThat(totalQuantity(aggressor.trades())).isEqualByComparingTo("0.00000003");
            assertThat(aggressor.trades().get(0).price()).isEqualByComparingTo("100.00000000");

            assertThat(aggressor.order().status()).isEqualTo(OrderStatus.FILLED);
            assertThat(aggressor.order().remainingQuantity()).isEqualByComparingTo("0");
            assertThat(aggressor.order().filledQuantity()).isEqualByComparingTo("0.00000003");
            assertThat(engine.findOrder(resting.order().id()).status()).isEqualTo(OrderStatus.FILLED);

            assertThat(engine.snapshot().asks()).isEmpty();
            assertThat(engine.snapshot().bids()).isEmpty();
            assertThat(engine.restingOrderCount()).isZero();
            assertThat(engine.snapshot().lastPrice()).isEqualByComparingTo("100.00000000");
        }

        @Test
        @DisplayName("One unit of the smallest scale fills three eighths of a resting sell")
        void tinyAggressorPartiallyFillsARestingSell() {
            MatchResult resting = sell("1.00000001", "0.00000008");

            MatchResult aggressor = buy("1.00000001", "0.00000003");

            assertThat(aggressor.order().status()).isEqualTo(OrderStatus.FILLED);
            assertThat(aggressor.trades()).hasSize(1);

            assertThat(engine.findOrder(resting.order().id()).status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
            assertThat(engine.findOrder(resting.order().id()).remainingQuantity()).isEqualByComparingTo("0.00000005");
            assertThat(engine.snapshot().asks()).containsExactly(new PriceLevel(bd("1.00000001"), bd("0.00000005"), 1));
        }
    }

    // ------------------------------------------------------------------ largest quantities

    @Nested
    @DisplayName("very large quantities are conserved exactly")
    class LargestQuantities {

        @Test
        @DisplayName("A billion units crossing a single level trade in full without rounding")
        void oneBillionCrossesASingleLevel() {
            MatchResult resting = sell("250.00", "1000000000");

            MatchResult aggressor = buy("250.00", "1000000000");

            assertThat(aggressor.trades()).hasSize(1);
            assertThat(aggressor.trades().get(0).quantity()).isEqualByComparingTo("1000000000");
            // A whole number quantity is conserved with no lost nor invented decimal.
            assertThat(totalQuantity(aggressor.trades())).isEqualByComparingTo("1000000000");
            assertThat(totalQuantity(aggressor.trades()).scale()).isZero();

            assertThat(aggressor.order().status()).isEqualTo(OrderStatus.FILLED);
            assertThat(aggressor.order().filledQuantity()).isEqualByComparingTo("1000000000");
            assertThat(engine.findOrder(resting.order().id()).filledQuantity()).isEqualByComparingTo("1000000000");
            assertThat(engine.findOrder(resting.order().id()).remainingQuantity()).isEqualByComparingTo("0");

            assertThat(engine.snapshot().bids()).isEmpty();
            assertThat(engine.snapshot().asks()).isEmpty();
            assertThat(engine.restingOrderCount()).isZero();
        }

        @Test
        @DisplayName("A billion split over two levels adds up to exactly one billion")
        void oneBillionSpreadOverTwoLevelsAddsUp() {
            sell("100.00", "600000000");
            sell("101.00", "400000000");

            MatchResult aggressor = marketBuy("1000000000");

            assertThat(aggressor.trades()).extracting(Trade::quantity)
                    .containsExactly(bd("600000000"), bd("400000000"));
            assertThat(totalQuantity(aggressor.trades())).isEqualByComparingTo("1000000000");

            assertThat(aggressor.order().status()).isEqualTo(OrderStatus.FILLED);
            assertThat(engine.snapshot().asks()).isEmpty();
            assertThat(engine.snapshot().lastPrice()).isEqualByComparingTo("101.00");
        }
    }

    // ------------------------------------------------------------------ price improvement

    @Nested
    @DisplayName("price improvement is always granted at the resting price")
    class PriceImprovement {

        @Test
        @DisplayName("A LIMIT buy of 4 at 102 improves twice, at 100 then at 101")
        void limitBuyImprovesAcrossTwoLevelsAndIsFullyFilled() {
            sell("100.00", "2");
            sell("101.00", "2");
            sell("102.00", "2");

            MatchResult aggressor = buy("102.00", "4");

            // Two trades, both at the resting price: the aggressor pays 100 then 101, never 102.
            assertThat(aggressor.trades()).extracting(Trade::price, Trade::quantity)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(bd("100.00"), bd("2")),
                            org.assertj.core.groups.Tuple.tuple(bd("101.00"), bd("2")));
            assertThat(totalQuantity(aggressor.trades())).isEqualByComparingTo("4");

            // Nothing is left to rest: the buy asked for exactly the two levels it consumed.
            assertThat(aggressor.order().status()).isEqualTo(OrderStatus.FILLED);
            assertThat(aggressor.order().remainingQuantity()).isEqualByComparingTo("0");

            // The untouched level is the one the aggressor was willing to pay.
            MatchingEngine.BookSnapshot snapshot = engine.snapshot();
            assertThat(snapshot.asks()).containsExactly(new PriceLevel(bd("102.00"), bd("2"), 1));
            assertThat(snapshot.bids()).isEmpty();
            assertThat(snapshot.bestAsk()).isEqualByComparingTo("102.00");
            assertThat(snapshot.spread()).isNull();
        }

        @Test
        @DisplayName("lastPrice is the resting price of the last fill of a multi level sweep")
        void lastPriceIsThePriceOfTheLastFill() {
            sell("100.00", "2");
            sell("101.00", "2");
            sell("102.00", "2");

            MatchResult aggressor = buy("102.00", "4");

            assertThat(aggressor.trades()).hasSize(2);
            assertThat(engine.snapshot().lastPrice()).isEqualByComparingTo("101.00");
            assertThat(engine.stats().lastPrice()).isEqualByComparingTo("101.00");
        }
    }

    // ------------------------------------------------------------------ no cross

    @Nested
    @DisplayName("an aggressor strictly between two levels rests without crossing")
    class StrictlyBetweenLevels {

        @Test
        @DisplayName("A buy at 99 rests as the new best bid between 98 and the ask at 101")
        void buyBetweenTwoBidLevelsBecomesTheBestBid() {
            sell("101.00", "5");
            buy("98.00", "3");

            MatchResult aggressor = buy("99.00", "2");

            assertThat(aggressor.trades()).isEmpty();
            assertThat(aggressor.order().status()).isEqualTo(OrderStatus.NEW);
            assertThat(aggressor.order().price()).isEqualByComparingTo("99.00");

            MatchingEngine.BookSnapshot snapshot = engine.snapshot();
            assertThat(snapshot.bestBid()).isEqualByComparingTo("99.00");
            assertThat(snapshot.bids()).containsExactly(
                    new PriceLevel(bd("99.00"), bd("2"), 1),
                    new PriceLevel(bd("98.00"), bd("3"), 1));
            assertThat(snapshot.asks()).containsExactly(new PriceLevel(bd("101.00"), bd("5"), 1));
            assertThat(snapshot.spread()).isEqualByComparingTo("2");
            assertThat(engine.restingOrderCount()).isEqualTo(3);
            assertThat(snapshot.lastPrice()).isNull();
        }

        @Test
        @DisplayName("A sell at 102 rests as the new best ask between the bid at 99 and 103")
        void sellBetweenTwoAskLevelsBecomesTheBestAsk() {
            buy("99.00", "5");
            sell("103.00", "3");

            MatchResult aggressor = sell("102.00", "2");

            assertThat(aggressor.trades()).isEmpty();
            assertThat(aggressor.order().status()).isEqualTo(OrderStatus.NEW);
            assertThat(aggressor.order().price()).isEqualByComparingTo("102.00");

            MatchingEngine.BookSnapshot snapshot = engine.snapshot();
            assertThat(snapshot.bestAsk()).isEqualByComparingTo("102.00");
            assertThat(snapshot.asks()).containsExactly(
                    new PriceLevel(bd("102.00"), bd("2"), 1),
                    new PriceLevel(bd("103.00"), bd("3"), 1));
            assertThat(snapshot.bids()).containsExactly(new PriceLevel(bd("99.00"), bd("5"), 1));
            assertThat(snapshot.spread()).isEqualByComparingTo("3");
            assertThat(engine.restingOrderCount()).isEqualTo(3);
            assertThat(snapshot.lastPrice()).isNull();
        }

        @Test
        @DisplayName("A buy priced exactly at the ask crosses once, at that price")
        void buyExactlyAtTheAskCrossesOnce() {
            MatchResult resting = sell("100.00", "3");

            MatchResult aggressor = buy("100.00", "3");

            assertThat(aggressor.trades()).hasSize(1);
            assertThat(aggressor.trades().get(0).price()).isEqualByComparingTo("100.00");
            assertThat(aggressor.trades().get(0).sellOrderId()).isEqualTo(resting.order().id());
            assertThat(aggressor.order().status()).isEqualTo(OrderStatus.FILLED);
            assertThat(engine.findOrder(resting.order().id()).status()).isEqualTo(OrderStatus.FILLED);

            assertThat(engine.snapshot().asks()).isEmpty();
            assertThat(engine.snapshot().bids()).isEmpty();
            assertThat(engine.snapshot().bestBid()).isNull();
            assertThat(engine.snapshot().bestAsk()).isNull();
            assertThat(engine.snapshot().lastPrice()).isEqualByComparingTo("100.00");
        }
    }

    // ------------------------------------------------------------------ scale

    @Nested
    @DisplayName("the BigDecimal scale does not split a price level")
    class PriceScale {

        @Test
        @DisplayName("A buy of 100 and a buy of 100.00000000 share one single level")
        void sameValueWithDifferentScaleIsOneLevel() {
            buy("100", "1.5");
            buy("100.00000000", "2.50");

            MatchingEngine.BookSnapshot snapshot = engine.snapshot();

            assertThat(snapshot.bids()).hasSize(1);
            PriceLevel level = snapshot.bids().get(0);
            assertThat(level.price()).isEqualByComparingTo("100");
            // The first inserted key is kept as the level key, so the trailing zeros never appear.
            assertThat(level.price().toPlainString()).isEqualTo("100");
            assertThat(level.quantity()).isEqualByComparingTo("4");
            assertThat(level.orderCount()).isEqualTo(2);
            assertThat(snapshot.bestBid()).isEqualByComparingTo("100");
            assertThat(engine.restingOrderCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("A price level is found again by its exact value, whatever scale the aggressor uses")
        void aggressorMatchesALevelWrittenWithAnotherScale() {
            sell("100.00000000", "2");

            MatchResult aggressor = buy("100", "2");

            assertThat(aggressor.trades()).hasSize(1);
            assertThat(aggressor.trades().get(0).price()).isEqualByComparingTo("100");
            assertThat(aggressor.order().status()).isEqualTo(OrderStatus.FILLED);
            assertThat(engine.snapshot().asks()).isEmpty();
            assertThat(engine.restingOrderCount()).isZero();
        }
    }

    // ------------------------------------------------------------------ level removal

    @Nested
    @DisplayName("a fully consumed level disappears instead of staying empty")
    class ConsumedLevel {

        @Test
        @DisplayName("One aggressor empties a level of three orders and leaves the next level untouched")
        void aSingleAggressorConsumesOneWholeLevel() {
            sell("100.00", "1");
            sell("100.00", "1");
            sell("100.00", "1");
            sell("101.00", "5");
            assertThat(engine.restingOrderCount()).isEqualTo(4);

            MatchResult aggressor = buy("101.00", "3");

            assertThat(aggressor.trades()).hasSize(3);
            assertThat(aggressor.trades()).extracting(Trade::price).containsOnly(bd("100.00"));
            assertThat(totalQuantity(aggressor.trades())).isEqualByComparingTo("3");
            assertThat(aggressor.order().status()).isEqualTo(OrderStatus.FILLED);

            // The consumed level is gone, not left behind as an empty level.
            MatchingEngine.BookSnapshot snapshot = engine.snapshot();
            assertThat(snapshot.asks()).containsExactly(new PriceLevel(bd("101.00"), bd("5"), 1));
            assertThat(snapshot.bestAsk()).isEqualByComparingTo("101.00");
            assertThat(snapshot.spread()).isNull();
            assertThat(engine.restingOrderCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("The last level of the book is removed as well, leaving a completely empty book")
        void theLastLevelIsRemovedToo() {
            sell("100.00", "1");
            sell("100.00", "1");

            MatchResult aggressor = buy("100.00", "2");

            assertThat(aggressor.order().status()).isEqualTo(OrderStatus.FILLED);
            assertThat(aggressor.trades()).hasSize(2);
            assertThat(engine.snapshot().asks()).isEmpty();
            assertThat(engine.snapshot().bestAsk()).isNull();
            assertThat(engine.restingOrderCount()).isZero();
        }
    }

    // ------------------------------------------------------------------ market order

    @Nested
    @DisplayName("a market order that exactly empties the book")
    class MarketOrderEmptyingTheBook {

        @Test
        @DisplayName("A market buy sized exactly like the book ends FILLED, empty book and a last price")
        void marketBuySizedExactlyLikeTheBookIsFilled() {
            sell("100.00", "2");
            sell("101.00", "3");
            assertThat(engine.restingOrderCount()).isEqualTo(2);

            MatchResult aggressor = marketBuy("5");

            assertThat(aggressor.trades()).extracting(Trade::price, Trade::quantity)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(bd("100.00"), bd("2")),
                            org.assertj.core.groups.Tuple.tuple(bd("101.00"), bd("3")));
            assertThat(totalQuantity(aggressor.trades())).isEqualByComparingTo("5");

            assertThat(aggressor.order().status()).isEqualTo(OrderStatus.FILLED);
            assertThat(aggressor.order().remainingQuantity()).isEqualByComparingTo("0");
            assertThat(aggressor.order().filledQuantity()).isEqualByComparingTo("5");
            assertThat(aggressor.order().price()).isNull();

            MatchingEngine.BookSnapshot snapshot = engine.snapshot();
            assertThat(snapshot.bids()).isEmpty();
            assertThat(snapshot.asks()).isEmpty();
            assertThat(snapshot.bestBid()).isNull();
            assertThat(snapshot.bestAsk()).isNull();
            assertThat(snapshot.spread()).isNull();
            assertThat(snapshot.lastPrice()).isEqualByComparingTo("101.00");
            assertThat(engine.restingOrderCount()).isZero();
        }
    }
}
