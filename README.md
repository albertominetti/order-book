# Order Book

A small, self-contained **in-memory order book / matching engine** exposed through a **Spring Boot REST API**.
It implements strict **price-time priority** matching for a single instrument, supports **limit** and
**market** orders, partial fills, cancellation, and an aggregated book snapshot.

No database, no external broker: everything lives in memory, which keeps the project easy to read,
run and test.

## Features

- Limit and market orders (BUY / SELL).
- Strict price-time priority: best price first, FIFO within the same price level.
- Partial fills; trades execute at the resting order price.
- Cancel a resting order.
- Aggregated order book snapshot with best bid, best ask and spread.
- Recent trades tape.
- Validation and consistent error responses (`201`, `200`, `400`, `404`, `409`, `422`).
- Thread-safe: a single lock guards all mutable state.

## Tech stack

- Java 25
- Spring Boot 3.5 (Web, Validation)
- springdoc-openapi (OpenAPI 3 + Swagger UI)
- Maven
- JUnit 5 + MockMvc

## Getting started

```bash
# run the tests
mvn test

# start the API on http://localhost:8080
mvn spring-boot:run
```

## Architecture

```
src/main/java/com/albertominetti/orderbook
├── OrderBookApplication.java     Spring Boot entry point
├── domain/                       Plain domain model
│   ├── Order, OrderView          order entity + read-only view
│   ├── Side, OrderType           BUY/SELL, LIMIT/MARKET
│   ├── OrderStatus               NEW, PARTIALLY_FILLED, FILLED, CANCELLED
│   ├── PriceLevel                aggregated price level
│   └── Trade                     an executed trade
├── engine/
│   ├── MatchingEngine            the book + matching loop (core logic)
│   └── MatchResult               order + generated trades
├── service/
│   ├── OrderService              application-facing facade
│   └── EngineConfiguration       Spring wiring of the engine
├── web/
│   ├── OrderController           REST endpoints
│   └── GlobalExceptionHandler    maps exceptions to HTTP responses
├── dto/                          request/response records
└── exception/                    domain exceptions

src/main/resources/application.yml   configuration (YAML)
```

### Design decisions

- **Domain vs view.** `Order` is mutable and package-private to the engine; `OrderView` is the
  immutable projection that leaves the engine, so callers never mutate internal state.
- **Data structures.** Each side of the book is a `TreeMap<price, Deque<Order>>`. Bids are ordered
  descending, asks ascending, so the best price is always `firstKey()`. The `Deque` gives FIFO
  ordering inside a level. A `LinkedHashMap` keeps every order ever accepted, so terminal orders can
  still be fetched by id, and a bounded `ArrayDeque` keeps the most recent trades.
- **Concurrency.** A single `ReentrantLock` serializes matching. Reads (snapshot, lookups) take the
  same lock, so the book is never observed half-updated.
- **Clock injection.** The engine takes a `java.time.Clock`, which lets tests produce deterministic
  timestamps.

### How the matching works

1. An incoming (aggressive) order is matched against the opposite side while it still has quantity
   and the best opposite price is acceptable:
   - a BUY crosses when `bestAsk <= order.price` (always, for MARKET);
   - a SELL crosses when `bestBid >= order.price` (always, for MARKET).
2. At each step the aggressor trades `min(aggressor.remaining, resting.remaining)` with the oldest
   order at the best price level, at the resting order price.
3. The loop stops when the aggressor is filled or the next price does not cross.
4. Remaining quantity: the remainder of a **LIMIT** order rests on the book, the remainder of a
   **MARKET** order is discarded (the order ends `FILLED` when fully consumed, `CANCELLED` when only
   partially consumed).

## Configuration

Configuration lives in `src/main/resources/application.yml` (YAML), not in a `.properties` file. It
sets the application name, the HTTP port, the Jackson defaults, the springdoc paths and the log
levels.

## API documentation

springdoc-openapi is included, so an interactive API reference is generated at runtime:

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>

## API

Base path: `/api`. All payloads are JSON.

### POST /api/orders

Submit an order. Returns `201 Created` with a `Location` header.

```bash
curl -s -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"side":"BUY","type":"LIMIT","price":100.50,"quantity":10}'
```

```json
{
  "order": {
    "id": "6f1c1e2a-...",
    "side": "BUY",
    "type": "LIMIT",
    "price": 100.50,
    "quantity": 10,
    "remainingQuantity": 10,
    "filledQuantity": 0,
    "status": "NEW",
    "timestamp": "2026-10-04T09:00:00Z"
  },
  "trades": []
}
```

A market order omits `price`:

```bash
curl -s -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"side":"SELL","type":"MARKET","quantity":5}'
```

### DELETE /api/orders/{id}

Cancel a resting order. Returns the cancelled order, or `409` if the order cannot be cancelled
(already filled/cancelled, or a market order that never rests).

```bash
curl -s -X DELETE http://localhost:8080/api/orders/6f1c1e2a-...
```

### GET /api/orders/{id}

Fetch a single order, including terminal ones. `404` when the id is unknown.

```bash
curl -s http://localhost:8080/api/orders/6f1c1e2a-...
```

### GET /api/orderbook

Aggregated snapshot, best price first on both sides.

```bash
curl -s http://localhost:8080/api/orderbook
```

```json
{
  "bids": [{"price": 100.50, "quantity": 10, "orderCount": 1}],
  "asks": [{"price": 101.00, "quantity": 4, "orderCount": 1}],
  "bestBid": 100.50,
  "bestAsk": 101.00,
  "spread": 0.50,
  "lastPrice": null
}
```

### GET /api/trades

Recent trades, newest first. Optional `limit` query parameter (1..1000, default 50).

```bash
curl -s http://localhost:8080/api/trades?limit=20
```

### Errors

Every failing request returns the same shape:

```json
{
  "timestamp": "2026-10-04T09:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "path": "/api/orders",
  "violations": [{"field": "quantity", "message": "quantity must be greater than 0"}]
}
```

## Tests

```bash
mvn test
```

The suite covers the matching engine (full match, partial fill, price-time priority, market orders,
cancel, no-cross, multiple price levels, resting orders, the trade tape and input validation) plus an
end-to-end MockMvc integration test of the REST API.

## License

MIT. See [LICENSE](LICENSE).
