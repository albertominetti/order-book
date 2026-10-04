package com.albertominetti.orderbook.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A single order resting on (or matching against) the book.
 *
 * <p>Instances are <em>mutable</em>: {@code remainingQuantity} and {@code status}
 * are updated by the matching engine while it holds its lock, so all reads and
 * writes must happen under that lock.</p>
 */
public class Order {

    private final UUID id;
    private final Side side;
    private final OrderType type;
    private final BigDecimal price;
    private final BigDecimal quantity;
    private final Instant timestamp;

    private BigDecimal remainingQuantity;
    private OrderStatus status;

    public Order(Side side, OrderType type, BigDecimal price, BigDecimal quantity, Instant timestamp) {
        this.id = UUID.randomUUID();
        this.side = Objects.requireNonNull(side, "side must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.price = price;
        this.quantity = Objects.requireNonNull(quantity, "quantity must not be null");
        this.remainingQuantity = quantity;
        this.status = OrderStatus.NEW;
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    /** Visible for testing: allows the caller to inject a fixed id. */
    Order(UUID id, Side side, OrderType type, BigDecimal price, BigDecimal quantity, Instant timestamp) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.side = Objects.requireNonNull(side, "side must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.price = price;
        this.quantity = Objects.requireNonNull(quantity, "quantity must not be null");
        this.remainingQuantity = quantity;
        this.status = OrderStatus.NEW;
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    public UUID getId() {
        return id;
    }

    public Side getSide() {
        return side;
    }

    public OrderType getType() {
        return type;
    }

    /** {@code null} for MARKET orders. */
    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getRemainingQuantity() {
        return remainingQuantity;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public BigDecimal getFilledQuantity() {
        return quantity.subtract(remainingQuantity);
    }

    public boolean isResting() {
        return !status.isTerminal();
    }

    /**
     * Applies a fill of the given quantity and moves the status forward.
     * Engine-internal: only the matching engine may call this, and only under its lock.
     *
     * @return the quantity actually filled
     */
    public BigDecimal fill(BigDecimal fillQuantity) {
        if (fillQuantity.signum() <= 0 || fillQuantity.compareTo(remainingQuantity) > 0) {
            throw new IllegalArgumentException("invalid fill quantity " + fillQuantity + " for order " + id);
        }
        remainingQuantity = remainingQuantity.subtract(fillQuantity);
        status = remainingQuantity.signum() == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        return fillQuantity;
    }

    /**
     * Marks the order as cancelled; only valid while the order is still on the book.
     * Engine-internal: only the matching engine may call this, and only under its lock.
     */
    public void cancel() {
        if (status.isTerminal()) {
            throw new IllegalStateException("order " + id + " is already " + status);
        }
        status = OrderStatus.CANCELLED;
    }

    /** Takes an immutable copy; must be called while the matching engine lock is held. */
    public OrderView toView() {
        return new OrderView(id, side, type, price, quantity, remainingQuantity, status, timestamp);
    }

    @Override
    public String toString() {
        return "Order{id=" + id + ", side=" + side + ", type=" + type + ", price=" + price
                + ", quantity=" + quantity + ", remainingQuantity=" + remainingQuantity
                + ", status=" + status + '}';
    }
}