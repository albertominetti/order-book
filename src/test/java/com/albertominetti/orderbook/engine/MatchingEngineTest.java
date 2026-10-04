package com.albertominetti.orderbook.engine;

import com.albertominetti.orderbook.domain.InstrumentStats;
import com.albertominetti.orderbook.domain.OrderStatus;
import com.albertominetti.orderbook.domain.OrderType;
import com.albertominetti.orderbook.domain.OrderView;
import com.albertominetti.orderbook.domain.PriceLevel;
import com.albertominetti.orderbook.domain.Side;
import com.albertominetti.orderbook.domain.Trade;
import com.albertominetti.orderbook.exception.OrderNotFoundException;
import com.albertominetti.orderbook.exception.OrderStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the matching engine: price-time priority, partial fills,
 * market orders, cancellation and book aggregation.
 */
class MatchingEngineTest {

    /** Frozen clock so timestamps are deterministic. */
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine("TEST", FIXED_CLOCK);
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

    private MatchResult marketSell(String quantity) {
        return engine.submit(Side.SELL, OrderType.MARKET, null, bd(quantity));
    }

    // ------------------------------------------------------------------ resting

    @Nested
    @DisplayName("orders that cannot trade rest on the book")
    class Resting {

        @Test
        void buyLimitOrderRestsWithFullQuantityRemaining() {
            MatchResult result = buy("100.00", "5");

            assertEquals(OrderStatus.NEW, result.order().status());
            assertThat(result.order().remainingQuantity()).isEqualByComparingTo("5");
            assertThat(result.trades()).isEmpty();
            assertThat(engine.restingOrderCount()).isEqualTo(1);
        }

        @Test
        void sellLimitOrderRestsAndAppearsInTheSnapshot() {
            sell("101.25", "3");

            MatchingEngine.BookSnapshot snapshot = engine.snapshot();
            assertThat(snapshot.asks()).containsExactly(new PriceLevel(bd("101.25"), bd("3"), 1));
            assertThat(snapshot.bids()).isEmpty();
            assertThat(snapshot.bestAsk()).isEqualByComparingTo("101.25");
            assertThat(snapshot.bestBid()).isNull();
            assertThat(snapshot.spread()).isNull();
            assertThat(snapshot.lastPrice()).isNull();
        }

        @Test
        void pricesAreKeptWithTheSubmittedScale() {
            OrderView order = buy("99.12345678", "1.00000001").order();

            assertThat(order.price()).isEqualByComparingTo("99.12345678");
            assertThat(engine.snapshot().bestBid()).isEqualByComparingTo("99.12345678");
        }

        @Test
        void orderIsRetrievableByIdWithItsOwnTimestamp() {
            MatchResult submitted = buy("100.00", "1");
            UUID id = submitted.order().id();

            OrderView fetched = engine.findOrder(id);

            assertThat(fetched.id()).isEqualTo(id);
            assertThat(fetched.side()).isEqualTo(Side.BUY);
            assertThat(fetched.type()).isEqualTo(OrderType.LIMIT);
            assertThat(fetched.quantity()).isEqualByComparingTo("1");
            assertThat(fetched.timestamp()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
        }
    }

    // ------------------------------------------------------------------ symbol

    @Nested
    @DisplayName("every order and trade carries the instrument symbol")
    class Symbol {

        @Test
        void engineNormalizesItsOwnSymbol() {
            assertThat(new MatchingEngine(" btc-usd ").symbol()).isEqualTo("BTC-USD");
        }

        @Test
        void engineRejectsAMalformedSymbol() {
            assertThatThrownBy(() -> new MatchingEngine("BT C"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("symbol");
        }

        @Test
        void restingAndFilledOrdersExposeTheSymbol() {
            MatchResult resting = sell("100.00", "5");
            MatchResult aggressor = buy("100.00", "5");

            assertThat(resting.order().symbol()).isEqualTo("TEST");
            assertThat(aggressor.order().symbol()).isEqualTo("TEST");
            assertThat(engine.findOrder(resting.order().id()).symbol()).isEqualTo("TEST");
            assertThat(aggressor.trades()).allSatisfy(trade -> assertThat(trade.symbol()).isEqualTo("TEST"));
        }

        @Test
        void statsAreScopedToTheInstrument() {
            sell("101.00", "2");

            InstrumentStats stats = engine.stats();

            assertThat(stats.symbol()).isEqualTo("TEST");
            assertThat(stats.restingOrders()).isEqualTo(1);
            assertThat(stats.bestBid()).isNull();
            assertThat(stats.bestAsk()).isEqualByComparingTo("101.00");
            assertThat(stats.lastPrice()).isNull();
        }

        @Test
        void statsReportTheLastPriceOnceSomethingTraded() {
            sell("100.00", "1");
            buy("100.00", "1");

            assertThat(engine.stats().lastPrice()).isEqualByComparingTo("100.00");
            assertThat(engine.stats().restingOrders()).isZero();
        }
    }

    // ------------------------------------------------------------------ matching

    @Nested
    @DisplayName("full match")
    class FullMatch {

        @Test
        void crossingLimitsFillBothOrdersCompletely() {
            MatchResult resting = sell("100.00", "5");
            MatchResult aggressor = buy("100.00", "5");

            assertEquals(OrderStatus.FILLED, aggressor.order().status());
            assertThat(aggressor.order().remainingQuantity()).isEqualByComparingTo("0");
            assertEquals(OrderStatus.FILLED, engine.findOrder(resting.order().id()).status());
            assertThat(aggressor.trades()).hasSize(1);
            assertThat(engine.restingOrderCount()).isZero();
        }

        @Test
        void tradeCarriesBothOrderIdsPriceAndQuantity() {
            MatchResult resting = sell("100.00", "5");
            MatchResult aggressor = buy("100.00", "5");

            Trade trade = aggressor.trades().get(0);
            assertThat(trade.sellOrderId()).isEqualTo(resting.order().id());
            assertThat(trade.buyOrderId()).isEqualTo(aggressor.order().id());
            assertThat(trade.price()).isEqualByComparingTo("100.00");
            assertThat(trade.quantity()).isEqualByComparingTo("5");
            assertThat(trade.timestamp()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
        }

        @Test
        void aggressiveBuyCrossesAHigherAskAndTradesAtTheRestingPrice() {
            sell("100.00", "2");
            MatchResult aggressor = buy("105.00", "2");

            // Price improvement goes to the resting seller: the trade happens at 100.00.
            assertThat(aggressor.trades().get(0).price()).isEqualByComparingTo("100.00");
            assertEquals(OrderStatus.FILLED, aggressor.order().status());
            assertThat(engine.snapshot().asks()).isEmpty();
        }

        @Test
        void aggressiveSellCrossesALowerBidAndTradesAtTheRestingPrice() {
            MatchResult resting = buy("100.00", "2");
            MatchResult aggressor = sell("95.00", "2");

            assertThat(aggressor.trades().get(0).price()).isEqualByComparingTo("100.00");
            assertThat(aggressor.trades().get(0).buyOrderId()).isEqualTo(resting.order().id());
            assertThat(aggressor.trades().get(0).sellOrderId()).isEqualTo(aggressor.order().id());
            assertThat(engine.snapshot().bids()).isEmpty();
        }

        @Test
        void filledQuantityIsExposedOnTheOrderView() {
            MatchResult resting = buy("100.00", "2");
            MatchResult aggressor = sell("100.00", "2");

            OrderView order = engine.findOrder(resting.order().id());
            assertThat(order.filledQuantity()).isEqualByComparingTo("2");
            assertThat(order.remainingQuantity()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("partial fill")
    class PartialFill {

        @Test
        void smallBuyIsFilledAndTheRestingSellKeepsItsRemainderOnTheBook() {
            sell("100.00", "10");
            MatchResult aggressor = buy("100.00", "4");

            assertEquals(OrderStatus.FILLED, aggressor.order().status());
            assertThat(aggressor.order().remainingQuantity()).isEqualByComparingTo("0");
            assertThat(aggressor.trades()).hasSize(1);
            assertThat(aggressor.trades().get(0).quantity()).isEqualByComparingTo("4");

            // The sell order is only half executed and keeps resting.
            MatchingEngine.BookSnapshot snapshot = engine.snapshot();
            assertThat(snapshot.bids()).isEmpty();
            assertThat(snapshot.asks()).containsExactly(new PriceLevel(bd("100.00"), bd("6"), 1));
        }

        @Test
        void aggressorBiggerThanTheBookEndsPartiallyFilledAndRestsTheRemainder() {
            sell("100.00", "10");
            MatchResult aggressor = buy("100.00", "16");

            assertEquals(OrderStatus.PARTIALLY_FILLED, aggressor.order().status());
            assertThat(aggressor.order().remainingQuantity()).isEqualByComparingTo("6");
            assertThat(aggressor.trades().get(0).quantity()).isEqualByComparingTo("10");
            assertThat(engine.snapshot().bids()).containsExactly(new PriceLevel(bd("100.00"), bd("6"), 1));
        }

        @Test
        void restingSellIsPartiallyFilledAndKeepsItsRemainingQuantity() {
            MatchResult resting = sell("100.00", "10");
            buy("100.00", "4");

            OrderView order = engine.findOrder(resting.order().id());
            assertEquals(OrderStatus.PARTIALLY_FILLED, order.status());
            assertThat(order.remainingQuantity()).isEqualByComparingTo("6");
            assertThat(order.filledQuantity()).isEqualByComparingTo("4");
        }

        @Test
        void aggressorBiggerThanBookConsumesAllLiquidityAndRestsTheRest() {
            sell("100.00", "3");
            MatchResult aggressor = buy("100.00", "10");

            assertEquals(OrderStatus.PARTIALLY_FILLED, aggressor.order().status());
            assertThat(aggressor.order().remainingQuantity()).isEqualByComparingTo("7");
            assertThat(aggressor.trades()).hasSize(1);
            assertThat(engine.snapshot().bids()).containsExactly(new PriceLevel(bd("100.00"), bd("7"), 1));
            assertThat(engine.snapshot().asks()).isEmpty();
        }

        @Test
        void twoOrdersOnTheSameLevelAreConsumedInFifoOrder() {
            MatchResult first = sell("100.00", "4");
            MatchResult second = sell("100.00", "4");

            MatchResult aggressor = buy("100.00", "6");

            assertThat(aggressor.trades()).hasSize(2);
            assertThat(aggressor.trades().get(0).sellOrderId()).isEqualTo(first.order().id());
            assertThat(aggressor.trades().get(1).sellOrderId()).isEqualTo(second.order().id());
            assertThat(aggressor.trades().get(0).quantity()).isEqualByComparingTo("4");
            assertThat(aggressor.trades().get(1).quantity()).isEqualByComparingTo("2");
            assertEquals(OrderStatus.FILLED, engine.findOrder(first.order().id()).status());
            assertEquals(OrderStatus.PARTIALLY_FILLED, engine.findOrder(second.order().id()).status());
        }
    }

    @Nested
    @DisplayName("price-time priority")
    class PriceTimePriority {

        @Test
        void bestPriceIsConsumedBeforeWorsePrices() {
            MatchResult worse = sell("101.00", "5");
            MatchResult better = sell("100.00", "5");

            MatchResult aggressor = buy("101.00", "8");

            assertThat(aggressor.trades()).hasSize(2);
            assertThat(aggressor.trades().get(0).sellOrderId()).isEqualTo(better.order().id());
            assertThat(aggressor.trades().get(1).sellOrderId()).isEqualTo(worse.order().id());
            assertEquals(OrderStatus.FILLED, engine.findOrder(better.order().id()).status());
            assertEquals(OrderStatus.PARTIALLY_FILLED, engine.findOrder(worse.order().id()).status());
        }

        @Test
        void sellConsumesTheHighestBidFirst() {
            MatchResult lower = buy("99.00", "5");
            MatchResult higher = buy("100.00", "5");

            MatchResult aggressor = sell("99.00", "8");

            assertThat(aggressor.trades()).hasSize(2);
            assertThat(aggressor.trades().get(0).buyOrderId()).isEqualTo(higher.order().id());
            assertThat(aggressor.trades().get(1).buyOrderId()).isEqualTo(lower.order().id());
            assertThat(aggressor.trades().get(1).price()).isEqualByComparingTo("99.00");
        }

        @Test
        void oldestOrderOnABetterLevelWinsOverANewerOneAtTheSamePrice() {
            MatchResult older = sell("100.00", "3");
            sleepOneMillis();
            MatchResult newer = sell("100.00", "3");

            MatchResult aggressor = buy("100.00", "4");

            assertThat(aggressor.trades()).hasSize(2);
            assertThat(aggressor.trades().get(0).sellOrderId()).isEqualTo(older.order().id());
            assertThat(aggressor.trades().get(1).sellOrderId()).isEqualTo(newer.order().id());
        }

        @Test
        void timePriorityIsFifoWithinASinglePriceLevel() {
            List<UUID> submitted = java.util.stream.IntStream.range(0, 5)
                    .mapToObj(i -> sell("100.00", "1").order().id())
                    .toList();

            MatchResult aggressor = buy("100.00", "5");

            assertThat(aggressor.trades()).hasSize(5);
            assertThat(aggressor.trades().stream().map(Trade::sellOrderId).toList())
                    .containsExactlyElementsOf(submitted);
        }
    }

    @Nested
    @DisplayName("market orders")
    class MarketOrders {

        @Test
        void marketBuySweepsSeveralAskLevels() {
            sell("100.00", "2");
            sell("101.00", "3");
            sell("102.00", "4");

            MatchResult aggressor = marketBuy("7");

            assertEquals(OrderStatus.FILLED, aggressor.order().status());
            assertThat(aggressor.trades()).hasSize(3);
            assertThat(aggressor.trades()).extracting(Trade::price, Trade::quantity)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(bd("100.00"), bd("2")),
                            org.assertj.core.groups.Tuple.tuple(bd("101.00"), bd("3")),
                            org.assertj.core.groups.Tuple.tuple(bd("102.00"), bd("2")));
            assertThat(engine.snapshot().asks()).containsExactly(new PriceLevel(bd("102.00"), bd("2"), 1));
        }

        @Test
        void marketSellSweepsSeveralBidLevels() {
            buy("102.00", "4");
            buy("101.00", "3");
            buy("100.00", "2");

            MatchResult aggressor = marketSell("9");

            assertEquals(OrderStatus.FILLED, aggressor.order().status());
            assertThat(aggressor.trades()).hasSize(3);
            assertThat(aggressor.trades()).extracting(Trade::price, Trade::quantity)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(bd("102.00"), bd("4")),
                            org.assertj.core.groups.Tuple.tuple(bd("101.00"), bd("3")),
                            org.assertj.core.groups.Tuple.tuple(bd("100.00"), bd("2")));
            assertThat(engine.snapshot().bids()).isEmpty();
        }

        @Test
        void unfilledMarketRemainderIsDiscardedAndOrderIsCancelled() {
            sell("100.00", "2");
            MatchResult aggressor = marketBuy("5");

            assertEquals(OrderStatus.CANCELLED, aggressor.order().status());
            assertThat(aggressor.order().remainingQuantity()).isEqualByComparingTo("3");
            assertThat(aggressor.trades()).hasSize(1);
            assertThat(engine.restingOrderCount()).isZero();
            assertThat(engine.snapshot().bids()).isEmpty();
            assertThat(engine.snapshot().asks()).isEmpty();
        }

        @Test
        void marketOrderOnAnEmptyBookDoesNotTradeAndIsCancelled() {
            MatchResult aggressor = marketBuy("3");

            assertEquals(OrderStatus.CANCELLED, aggressor.order().status());
            assertThat(aggressor.trades()).isEmpty();
            assertThat(engine.recentTrades(10)).isEmpty();
        }

        @Test
        void marketOrderHasNoPrice() {
            MatchResult aggressor = marketSell("1");

            assertThat(aggressor.order().price()).isNull();
            assertThat(aggressor.order().type()).isEqualTo(OrderType.MARKET);
        }

        @Test
        void marketOrderCannotBeCancelledBecauseItNeverRests() {
            sell("100.00", "1");
            MatchResult aggressor = marketBuy("1");

            assertThatThrownBy(() -> engine.cancel(aggressor.order().id()))
                    .isInstanceOf(OrderStateException.class)
                    .hasMessageContaining("cannot be cancelled");
        }

        @Test
        void cancelledMarketOrderCannotBeCancelledTwice() {
            sell("100.00", "1");
            MatchResult aggressor = marketBuy("5");

            assertThatThrownBy(() -> engine.cancel(aggressor.order().id()))
                    .isInstanceOf(OrderStateException.class)
                    .hasMessageContaining("CANCELLED");
        }
    }

    @Nested
    @DisplayName("no cross when prices do not overlap")
    class NoCross {

        @Test
        void buyBelowAskAndSellAboveBidDoNotTrade() {
            MatchResult buyResult = buy("99.00", "5");
            MatchResult sellResult = sell("100.00", "3");

            assertThat(buyResult.trades()).isEmpty();
            assertThat(sellResult.trades()).isEmpty();
            assertEquals(OrderStatus.NEW, buyResult.order().status());
            assertEquals(OrderStatus.NEW, sellResult.order().status());
            assertThat(engine.restingOrderCount()).isEqualTo(2);
        }

        @Test
        void oneTickGapKeepsBothSidesRestingAndTheSpreadIsPositive() {
            buy("99.99", "5");
            sell("100.00", "3");

            MatchingEngine.BookSnapshot snapshot = engine.snapshot();

            assertThat(snapshot.bestBid()).isEqualByComparingTo("99.99");
            assertThat(snapshot.bestAsk()).isEqualByComparingTo("100.00");
            assertThat(snapshot.spread()).isEqualByComparingTo("0.01");
        }

        @Test
        void buyAtOrAboveTheAskCrosses() {
            sell("100.00", "3");

            assertThat(buy("100.00", "1").trades()).hasSize(1);
        }

        @Test
        void sellAtOrBelowTheBidCrosses() {
            buy("100.00", "3");

            assertThat(sell("100.00", "1").trades()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("multiple price levels")
    class MultiplePriceLevels {

        @Test
        void bidsAreAggregatedPerLevelAndSortedBestFirst() {
            buy("98.00", "1");
            buy("100.00", "2");
            buy("99.00", "3");
            buy("100.00", "4");

            MatchingEngine.BookSnapshot snapshot = engine.snapshot();

            assertThat(snapshot.bids()).containsExactly(
                    new PriceLevel(bd("100.00"), bd("6"), 2),
                    new PriceLevel(bd("99.00"), bd("3"), 1),
                    new PriceLevel(bd("98.00"), bd("1"), 1));
            assertThat(snapshot.bestBid()).isEqualByComparingTo("100.00");
        }

        @Test
        void asksAreAggregatedPerLevelAndSortedBestFirst() {
            sell("102.00", "1");
            sell("100.00", "2");
            sell("101.00", "3");
            sell("100.00", "4");

            MatchingEngine.BookSnapshot snapshot = engine.snapshot();

            assertThat(snapshot.asks()).containsExactly(
                    new PriceLevel(bd("100.00"), bd("6"), 2),
                    new PriceLevel(bd("101.00"), bd("3"), 1),
                    new PriceLevel(bd("102.00"), bd("1"), 1));
            assertThat(snapshot.bestAsk()).isEqualByComparingTo("100.00");
        }

        @Test
        void emptyBookHasNoBestPricesAndNoSpread() {
            MatchingEngine.BookSnapshot snapshot = engine.snapshot();

            assertThat(snapshot.bids()).isEmpty();
            assertThat(snapshot.asks()).isEmpty();
            assertThat(snapshot.bestBid()).isNull();
            assertThat(snapshot.bestAsk()).isNull();
            assertThat(snapshot.spread()).isNull();
        }

        @Test
        void lastPriceIsThePriceOfTheMostRecentTrade() {
            sell("100.00", "1");
            sell("101.00", "1");
            marketBuy("1");
            marketBuy("1");

            assertThat(engine.snapshot().lastPrice()).isEqualByComparingTo("101.00");
        }

        @Test
        void levelsDisappearOnceFullyConsumed() {
            sell("100.00", "1");
            sell("101.00", "1");
            marketBuy("1");

            MatchingEngine.BookSnapshot snapshot = engine.snapshot();

            assertThat(snapshot.asks()).containsExactly(new PriceLevel(bd("101.00"), bd("1"), 1));
            assertThat(snapshot.spread()).isNull();
        }
    }

    // ------------------------------------------------------------------ cancel

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        void cancelRemovesTheRestingOrderFromTheBook() {
            MatchResult order = buy("100.00", "5");

            OrderView cancelled = engine.cancel(order.order().id());

            assertEquals(OrderStatus.CANCELLED, cancelled.status());
            assertThat(engine.restingOrderCount()).isZero();
            assertThat(engine.snapshot().bids()).isEmpty();
        }

        @Test
        void cancelledOrderCannotBeMatchedLater() {
            MatchResult buyOrder = buy("100.00", "5");
            engine.cancel(buyOrder.order().id());

            MatchResult aggressor = sell("100.00", "5");

            assertThat(aggressor.trades()).isEmpty();
            assertEquals(OrderStatus.NEW, aggressor.order().status());
        }

        @Test
        void cancelPreservesTheOriginalQuantityInTheHistory() {
            MatchResult order = buy("100.00", "5");
            engine.cancel(order.order().id());

            OrderView stored = engine.findOrder(order.order().id());

            assertThat(stored.quantity()).isEqualByComparingTo("5");
            assertThat(stored.remainingQuantity()).isEqualByComparingTo("5");
            assertEquals(OrderStatus.CANCELLED, stored.status());
        }

        @Test
        void partiallyFilledOrderCanBeCancelled() {
            sell("100.00", "10");
            // Bigger than the available liquidity, so the buy rests with a remainder.
            MatchResult buyOrder = buy("100.00", "20");

            assertEquals(OrderStatus.PARTIALLY_FILLED, buyOrder.order().status());
            OrderView cancelled = engine.cancel(buyOrder.order().id());

            assertEquals(OrderStatus.CANCELLED, cancelled.status());
            assertThat(engine.snapshot().bids()).isEmpty();
        }

        @Test
        void cancelKeepsTheOtherOrdersOfTheSameLevel() {
            MatchResult first = buy("100.00", "2");
            MatchResult second = buy("100.00", "3");

            engine.cancel(first.order().id());

            assertThat(engine.snapshot().bids()).containsExactly(new PriceLevel(bd("100.00"), bd("3"), 1));
            assertEquals(OrderStatus.NEW, engine.findOrder(second.order().id()).status());
        }

        @Test
        void cancellingAFilledOrderIsRejected() {
            MatchResult buyOrder = buy("100.00", "2");
            sell("100.00", "2");

            assertThatThrownBy(() -> engine.cancel(buyOrder.order().id()))
                    .isInstanceOf(OrderStateException.class)
                    .hasMessageContaining("FILLED");
        }

        @Test
        void cancellingTwiceIsRejected() {
            MatchResult order = buy("100.00", "2");
            engine.cancel(order.order().id());

            assertThatThrownBy(() -> engine.cancel(order.order().id()))
                    .isInstanceOf(OrderStateException.class)
                    .hasMessageContaining("CANCELLED");
        }

        @Test
        void cancellingAnUnknownIdFails() {
            assertThatThrownBy(() -> engine.cancel(UUID.randomUUID()))
                    .isInstanceOf(OrderNotFoundException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void lookingUpAnUnknownIdFails() {
            assertThatThrownBy(() -> engine.findOrder(UUID.randomUUID()))
                    .isInstanceOf(OrderNotFoundException.class);
        }
    }

    // ------------------------------------------------------------------ validation

    @Nested
    @DisplayName("input validation")
    class InputValidation {

        @Test
        void quantityMustBePositive() {
            assertThatThrownBy(() -> buy("100.00", "0")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> buy("100.00", "-1")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> marketBuy("0")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void limitOrdersRequireAPositivePrice() {
            assertThatThrownBy(() -> engine.submit(Side.BUY, OrderType.LIMIT, null, bd("1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("price must be greater than zero");
        }

        @Test
        void marketOrdersMustNotCarryAPrice() {
            assertThatThrownBy(() -> engine.submit(Side.BUY, OrderType.MARKET, bd("100.00"), bd("1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be provided");
        }

        @Test
        void recentTradesRejectsOutOfRangeLimits() {
            assertThatThrownBy(() -> engine.recentTrades(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> engine.recentTrades(MatchingEngine.MAX_RECENT_TRADES + 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------ trades

    @Nested
    @DisplayName("trade tape")
    class TradeTape {

        @Test
        void recentTradesAreReturnedNewestFirst() {
            MatchResult first = sell("100.00", "1");
            buy("100.00", "1");
            MatchResult second = sell("101.00", "1");
            buy("101.00", "1");

            List<Trade> trades = engine.recentTrades(10);

            assertThat(trades).hasSize(2);
            assertThat(trades.get(0).sellOrderId()).isEqualTo(second.order().id());
            assertThat(trades.get(1).sellOrderId()).isEqualTo(first.order().id());
        }

        @Test
        void recentTradesHonoursTheLimit() {
            for (int i = 0; i < 5; i++) {
                sell("100.00", "1");
                buy("100.00", "1");
            }

            assertThat(engine.recentTrades(3)).hasSize(3);
            assertThat(engine.recentTrades(100)).hasSize(5);
        }

        @Test
        void tradeTapeIsEmptyBeforeAnythingTrades() {
            buy("100.00", "1");

            assertThat(engine.recentTrades(10)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    @DisplayName("concurrent submissions never lose or duplicate quantity")
    void concurrentSubmissionsKeepTheBookConsistent() throws InterruptedException {
        int ordersPerSide = 200;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(ordersPerSide * 2);

        for (int i = 0; i < ordersPerSide; i++) {
            pool.submit(() -> {
                await(startLine);
                engine.submit(Side.BUY, OrderType.LIMIT, bd("100.00"), bd("1"));
                finished.countDown();
            });
            pool.submit(() -> {
                await(startLine);
                engine.submit(Side.SELL, OrderType.LIMIT, bd("100.00"), bd("1"));
                finished.countDown();
            });
        }

        startLine.countDown();
        assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        List<Trade> trades = engine.recentTrades(MatchingEngine.MAX_RECENT_TRADES);
        assertThat(trades).hasSize(ordersPerSide);
        assertThat(trades).allSatisfy(trade -> {
            assertThat(trade.price()).isEqualByComparingTo("100.00");
            assertThat(trade.quantity()).isEqualByComparingTo("1");
        });
        // Equal quantity on both sides: everything must have been traded, nothing left resting.
        assertThat(engine.restingOrderCount()).isZero();
        assertThat(engine.snapshot().bids()).isEmpty();
        assertThat(engine.snapshot().asks()).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Guarantees a strictly increasing submission instant without slowing the test down. */
    private void sleepOneMillis() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
