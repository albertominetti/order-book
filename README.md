# Order Book

A small, self-contained **in-memory ORDER BOOK / matching engine** exposed through a **Spring Boot REST
API**, built to serve **MULTIPLE instruments** at once.

There is one matching engine (that is, one order book) per instrument, keyed by a `symbol`. Orders are
routed to the book of their own symbol, so instruments are fully isolated from each other and are
matched in parallel. Within a book the engine implements strict **price-time priority** matching for
**LIMIT** and **MARKET** orders, with partial fills, cancellation, an aggregated book snapshot and a
per-instrument trade tape.

No database, no external broker: everything lives in memory, which keeps the project easy to read,
run and test. An instrument exists from the moment its first order arrives and lives for the whole
application lifetime. Separately from that runtime state, a static catalogue of tradable symbols
(47 Swiss SIX names and the 503 S&P 500 constituents, shipped as `src/main/resources/instruments.tsv`)
is loaded at startup and served as a search endpoint, which is what the instrument picker of the web
app queries.

## Live environments

- **Production:** https://albertos-order-book.onrender.com (free plan, sleeps when idle). It runs the prebuilt GraalVM native image published to GHCR by the CI.
- **Preview:** https://albertos-order-book-preview.onrender.com (free plan). Built from the `preview` branch on every push, so changes can be tested there before they reach `main`.

Useful paths on both: `/api/instruments`, and the API documentation at `/swagger-ui.html` (OpenAPI JSON at `/v3/api-docs`).

## Features

- MULTIPLE instruments, one independent book each, keyed by `symbol`.
- LIMIT and MARKET orders (BUY / SELL).
- Strict price-time priority: best price first, FIFO within the same price level.
- Partial fills; trades execute at the resting order price.
- Cancel a resting order.
- Aggregated order book snapshot with best bid, best ask and spread.
- List of active instruments with their stats.
- Instrument catalogue search over the Swiss SIX names and the S&P 500 constituents.
- Recent trades tape per instrument.
- Symbol normalization (trim + upper-case) and shape validation.
- Interactive web UI at `/ui` to submit orders and watch the market, with no frontend build.
- Validation and consistent error responses (`201`, `200`, `400`, `404`, `405`, `422`).
- Thread-safe: every engine has its own lock, so instruments never block each other.
- A Vue 3 web app at `/app/` that trades on the API from the browser.

## Tech stack

- Java 25
- Spring Boot 4.1.1 (Web, Validation)
- springdoc-openapi 3.x (OpenAPI 3 + Swagger UI)
- Vue 3 + Vite (single page frontend under `frontend/`)
- Maven
- JUnit 5 + MockMvc

## Getting started

```bash
# run the tests
mvn test

# start the API on http://localhost:8080
mvn spring-boot:run
```

Then open the web app at <http://localhost:8080/app/>, the documentation at
<http://localhost:8080/swagger-ui.html>, or submit an order:
Then open <http://localhost:8080/ui> for the interactive web UI, or
<http://localhost:8080/swagger-ui.html> for the API reference, or submit an order:

```bash
curl -s -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"BTC-USD","side":"BUY","type":"LIMIT","price":100.50,"quantity":10}'
```

## Deploy

The project ships with a multi-stage `Dockerfile` at the root and a Render Blueprint in
`render.yaml`, so the same artifact runs locally and on Render.

### Run the image locally

```bash
# build the image
docker build -t order-book .

# run it on http://localhost:8080
docker run -p 8080:8080 order-book
```

The build stage compiles the jar with Maven, the runtime stage copies it into a slim
`eclipse-temurin:25-jre` image and runs it as a non-root user. `JAVA_TOOL_OPTIONS` caps the heap at
75% of the available memory and selects the Serial GC, so the JVM stays comfortable on a small
instance.

### Container image on GitHub Container Registry

The same `Dockerfile` is built and pushed to the GitHub Container Registry by the GitHub Actions
workflow `.github/workflows/docker-publish.yml`, as `ghcr.io/albertominetti/order-book`. It runs on
every push to `main` or to a feature branch, on every `v*` tag, and on demand from the **Actions**
tab with **Run workflow**.

Each run produces:

- `latest`, but only on `main`, the default branch, so `latest` always tracks the default branch;
- the branch name on every other branch, for example `deploy/docker-render`;
- `sha-<short-sha>` on every push, an immutable tag that always points to that exact commit;
- the version without the leading `v` on `v*` tags, so `v1.2.0` also publishes `1.2.0`.

Pull and run the published image:

```bash
# pull the image built from main
docker pull ghcr.io/albertominetti/order-book:latest

# run it on http://localhost:8080
docker run -p 8080:8080 ghcr.io/albertominetti/order-book:latest
```

A new package is private by default, so a private image needs a login first:

```bash
echo "$GITHUB_TOKEN" | docker login ghcr.io -u <your-github-user> --password-stdin
```

The visibility can be changed to public in the package settings
(**Settings > Packages > order-book > Change visibility**). Once the package is public, the
`docker pull` above works without any login.

### Deploy on Render with the Blueprint

1. Push this repository to GitHub, GitLab or Bitbucket.
2. In the Render dashboard choose **New > Blueprint**, then connect the repository. Render reads
   `render.yaml` from the repository root and creates everything it declares, no manual
   configuration needed.
3. Click **Apply**. Render pulls and runs the prebuilt GraalVM native image from GHCR
   (`ghcr.io/albertominetti/order-book:native`) on the `order-book` free plan in the
   `frankfurt` region, with automatic deploys enabled on every commit. The GHCR package
   must be public for Render to pull it anonymously; if it is private, add a registry
   credential in Render (Workspace Settings) and reference it via `image.creds` in
   `render.yaml`.

The Blueprint declares no secrets, because the API needs none: it keeps all state in memory.

On the free plan the service **sleeps after a period of inactivity** and the first request after a
sleep takes a few seconds while the instance wakes up. The health check polls `/api/instruments`,
and `PORT` is assigned by Render and picked up by `server.port`.

### Try the deployed service

Once deployed, the whole API is served on the Render URL, so nothing changes but the host:

- Swagger UI: `https://<service>.onrender.com/swagger-ui.html`
- Web UI: `https://<service>.onrender.com/ui`
- REST API: `https://<service>.onrender.com/api/instruments`

```bash
curl -s https://<service>.onrender.com/api/instruments
```

## Architecture

```
src/main/java/com/albertominetti/orderbook
├── OrderBookApplication.java     Spring Boot entry point
├── domain/                       Plain domain model
│   ├── Order                     mutable order entity, carries its symbol
│   ├── OrderView                 immutable read-only projection of an order
│   ├── Side, OrderType           BUY/SELL, LIMIT/MARKET
│   ├── OrderStatus               NEW, PARTIALLY_FILLED, FILLED, CANCELLED
│   ├── SymbolRules               symbol normalization and validation
│   ├── InstrumentRef             a catalogue entry: symbol, name and market
│   ├── PriceLevel                aggregated price level
│   ├── InstrumentStats           per-instrument summary
│   └── Trade                     an executed trade, carries its symbol
├── engine/
│   ├── MatchingEngine            single-instrument book + matching loop (core logic)
│   └── MatchResult               order + generated trades
├── service/
│   ├── MarketRegistry            one engine per symbol, created lazily
│   ├── InstrumentCatalog         instrument catalogue loaded from instruments.tsv, searched by symbol or name
│   ├── OrderService              application facade, routes every request by symbol
│   └── EngineConfiguration       Spring wiring of the Clock
├── web/
│   ├── OrderController           REST endpoints
│   ├── HomeController            landing page at /, links to the web app and the documentation
│   ├── SpaController             forwards /app to the built Vue application
│   ├── HomeController            landing page at /, links to the documentation
│   ├── UiController              web UI at /ui
│   └── GlobalExceptionHandler    maps exceptions to HTTP responses
├── dto/                          request/response records
│   ├── CreateOrderRequest        order payload, symbol included
│   ├── MatchResponse             order + trades returned by POST /api/orders
│   ├── OrderResponse             public representation of an order
│   ├── TradeResponse             public representation of a trade
│   ├── PriceLevelResponse        one aggregated price level
│   ├── OrderBookResponse         book snapshot of one instrument
│   ├── InstrumentStatsResponse   one instrument in the instrument list
│   └── ApiErrorResponse          the single error shape
└── exception/                    domain exceptions
    ├── InvalidOrderException     business rule broken       -> 400
    ├── OrderNotFoundException    unknown order id           -> 404
    ├── UnknownInstrumentException well formed unknown symbol -> 404
    └── OrderStateException       order lifecycle broken     -> 422

src/main/resources/application.yml   configuration (YAML)
src/main/resources/templates/
├── index.html                       landing page served at /
└── ui.html                          self contained web UI served at /ui

src/main/resources/instruments.tsv   instrument catalogue: # symbol<TAB>name<TAB>market

src/main/resources/templates/index.html   Thymeleaf landing page

frontend/                            Vue 3 + Vite single page application (built by Maven)
├── package.json                     npm scripts: dev, build, preview
├── vite.config.js                   base /app/, output target/classes/static/app, /api dev proxy
├── index.html                       entry document of the app
└── src/
    ├── main.js                      mounts the application
    ├── App.vue                      layout, instrument selection, polling and order state
    ├── api.js                       fetch wrapper over the relative /api paths, normalizes errors
    ├── usePoller.js                 one request in flight per stream, stops with the component
    └── components/
        ├── InstrumentSelect.vue     instrument picker, debounced search over the catalogue
        ├── OrderForm.vue            LIMIT/MARKET order entry
        ├── OrderBook.vue            live book, best bid/ask, spread, last price
        ├── Trades.vue               recent trades of the instrument
        ├── Instruments.vue          active instruments, click to switch
        └── MyOrders.vue             orders of this browser, with cancel, responsive on phones

src/test/java/com/albertominetti/orderbook
├── engine/MatchingEngineTest.java
├── service/MarketRegistryTest.java
├── service/InstrumentCatalogTest.java
├── web/OrderApiIntegrationTest.java
├── web/MultiInstrumentApiTest.java
├── web/InstrumentSearchApiTest.java
├── web/HomePageTest.java
└── web/SpaRoutingTest.java
└── web/OrderApiIntegrationTest.java, HomePageTest.java, UiPageTest.java
```

### Design decisions

- **One engine per instrument.** `MarketRegistry` holds a single `MatchingEngine` per symbol in a
  `ConcurrentHashMap`. An engine is created lazily, on the first order submitted for that symbol, and
  is then reused for the whole application lifetime. `computeIfAbsent` guarantees that exactly one
  engine is ever published per symbol, even when several threads submit the very first order of a new
  instrument at the same time.
- **Symbol is the key.** `MatchingEngine` is strictly single-instrument: its book, its trade tape and
  its orders are private to the instance, and every order and trade it produces carries that symbol.
  Orders on different symbols can therefore never be matched against each other, not even at the very
  same price.
- **Isolation and parallelism.** Each engine guards its own mutable state with its own
  `ReentrantLock`. Orders of the same instrument are matched atomically and the book is never observed
  half-updated, while different instruments are matched fully in parallel.
- **Routing and the global order index.** An order id is unique across the whole market but lives
  inside a single engine, so `OrderService` keeps a global `orderId -> symbol` index
  (`ConcurrentHashMap`). It routes every request by symbol and resolves that index first, which is why
  `GET /api/orders/{id}` and `DELETE /api/orders/{id}` work without asking the caller for a symbol, and
  cancelling an order never touches the book of another instrument.
- **Domain vs view.** `Order` is mutable and only the engine may change it, under its lock.
  `OrderView` is the immutable projection that leaves the engine, so callers never mutate internal
  state. `InstrumentStats` and `BookSnapshot` are computed under a single lock acquisition.
- **Symbol rules in one place.** `SymbolRules` normalizes a symbol (trim, then upper-case with
  `Locale.ROOT`) and validates its shape, `[A-Z0-9][A-Z0-9._-]{0,19}` after normalization, that is one
  to twenty characters starting with a letter or a digit. The same rule is exposed as a Bean
  Validation pattern, so body and path variables are checked the same way.
- **The catalogue is read-only reference data on the server.** `InstrumentCatalog` reads
  `classpath:instruments.tsv` once, when the singleton is created, and keeps the 550 entries with
  their symbol and name already lower-cased, so a search is a single scan with no allocation. A query
  is ranked in two passes: instruments whose symbol starts with it first, then those whose name
  contains it, each sorted by symbol. Listing an instrument in the catalogue never creates its book:
  the catalogue only suggests symbols, `POST /api/orders` still brings an instrument to life.
- **Data structures.** Each side of a book is a `TreeMap<price, Deque<Order>>`. Bids are ordered
  descending, asks ascending, so the best price is always `firstKey()`. The `Deque` gives FIFO
  ordering inside a level. A `LinkedHashMap` keeps every order ever accepted, so terminal orders can
  still be fetched by id, and a bounded `ArrayDeque` (`MAX_RECENT_TRADES = 1000`) keeps the most
  recent trades.
- **Clock injection.** Engines take a `java.time.Clock` (a `Clock.systemUTC()` bean from
  `EngineConfiguration`), which lets tests produce deterministic timestamps.

## How the matching works

1. An incoming (aggressive) order is matched against the opposite side while it still has quantity and
   the best opposite price is acceptable:
   - a BUY crosses when `bestAsk <= order.price` (always, for MARKET);
   - a SELL crosses when `bestBid >= order.price` (always, for MARKET).
2. The best price is consumed first, and inside a level the oldest order is filled first (FIFO). Fills
   can be partial.
3. At each step the aggressor trades `min(aggressor.remaining, resting.remaining)` with the oldest
   order at the best price level, **at the resting order price**, so price improvement always goes to
   the resting order.
4. The loop stops when the aggressor is filled or the next price does not cross.
5. Remaining quantity: the remainder of a **LIMIT** order rests on the book at its own price, the
   remainder of a **MARKET** order is discarded (the order ends `FILLED` when fully consumed,
   `CANCELLED` when only partially consumed).

Order statuses are `NEW`, `PARTIALLY_FILLED`, `FILLED` and `CANCELLED`. `FILLED` and `CANCELLED` are
terminal and can never change, so they are also the statuses that make a cancel impossible.

## Configuration

Configuration lives in `src/main/resources/application.yml` (YAML), not in a `.properties` file. It
sets the application name, the HTTP port (`${PORT:8080}`, so it reads the port provided by the
platform and falls back to `8080` locally), the Jackson defaults, the springdoc paths and the
log levels. Jackson is configured with `default-property-inclusion: non_null`, so optional fields such
as `price` on a MARKET order, or `bestAsk` on a one-sided book, are simply omitted from the JSON.

The build toolchain has its own properties in `pom.xml`: `node.version` and `npm.version`, the Node
and npm versions `frontend-maven-plugin` installs inside `frontend/`, plus
`frontend-maven-plugin.version` itself. Bumping `node.version` is all it takes to move the frontend
build to another Node LTS.
## Web UI

A small interactive page is served at <http://localhost:8080/ui>, so the API can be exercised from a
browser without writing a single `curl` command. It is a single Thymeleaf template
(`templates/ui.html`, served by `UiController`) with inline CSS and plain vanilla JavaScript: no
frontend build, no dependency and no external asset, so the deployed service serves exactly the same
page as a local run.

The page calls the REST API from the browser with relative URLs on the same origin, and it offers:

- an **instrument bar** with a symbol field, quick buttons for `BTC-USD` and `ETH-USD`, and the
  active symbol shown next to them;
- an **order entry form** (`POST /api/orders`) with side, type, price and quantity, where the price is
  required for a LIMIT order and disabled for a MARKET order, followed by the status of the created
  order and of the trades it generated;
- an **order book panel** with best bid, best ask, spread, last price, bids from the highest price
  down and asks from the lowest price up;
- a **trades panel** with the last 20 trades, newest first;
- an **instruments panel** where clicking a row switches the active symbol;
- a **my orders panel** that remembers, in browser memory only, the orders submitted from that page,
  with a `Cancel` button on each of them plus a field to cancel an arbitrary order id
  (`DELETE /api/orders/{id}`);
- a **status area** that shows the `code` and the `message` of the uniform API error payload when a
  call fails.

The book, the trades and the instrument list are polled once per second, with at most one request per
stream in flight, so a slow answer makes a tick skip instead of piling requests up. The page lives in
memory like the rest of the project: reloading it forgets the tracked orders, since the ids are kept
in the page only.

## API documentation

springdoc-openapi is included, so an interactive API reference is generated at runtime:

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>

The root path <http://localhost:8080/> serves a small landing page (`HomeController`) that describes
the service, links to the web app at `/app/` and to Swagger UI and to the raw OpenAPI JSON, so
opening the application in a browser is enough to find everything.

## Frontend (Vue 3 + Vite)

A single page application written with **Vue 3** and built by **Vite** drives the very same REST API
from the browser. It is a standalone Vite project under [`frontend/`](frontend) and it is built by
[Maven](pom.xml): `com.github.eirslett:frontend-maven-plugin` downloads its own Node and npm, runs
`npm install` and then `vite build`.

The bundle is written to `target/classes/static/app`, so it ends up **in the jar and in the GraalVM
native image** and is served by Spring Boot as a static resource at <http://localhost:8080/app/>.
`SpaController` forwards `/app` and `/app/` to `/app/index.html`, and the landing page at `/` links
to it with a prominent "Open the web app (Vue 3)" entry.

What it does:

- switch instrument: a single searchable dropdown, the only instrument field of the page. It has no
  static list any more: every keystroke queries the backend catalogue with
  `GET /api/instruments/search?q=<term>&limit=50`, debounced by 200 ms, with the request in flight
  aborted and stale answers ignored, so at most one search is outstanding. Rows show symbol, name
  and market, ArrowUp / ArrowDown / Enter / Escape and the mouse all work, and a ticker nobody lists
  is still offered as a free form "Use \<TICKER\>" row;
- show the active instruments of `GET /api/instruments`, clickable to switch;
- submit orders, BUY or SELL, LIMIT or MARKET, through `POST /api/orders`, with the price required
  only for a LIMIT order;
- show the live book of the selected instrument from `GET /api/instruments/{symbol}/orderbook`, with
  the best bid, the best ask, the spread and the last price;
- show the last 20 trades from `GET /api/instruments/{symbol}/trades?limit=20`;
- keep the orders submitted from the browser and cancel them with `DELETE /api/orders/{id}`, with a
  table that fits on a phone: below 600px each order becomes a card and the Cancel button gets its
  own full width row, above it the table sits in a horizontally scrollable container;
- poll every stream once per second, never with more than one request in flight per stream, and stop
  polling when the page is left;
- show an empty state per panel and the `code` and `message` of the uniform API error payload when a
  call fails.

All calls use relative `/api` paths, so the app always talks to the same origin that served it: no
configuration and no CORS. The only external piece is Vue itself, which is bundled.

Because the frontend build is bound to the `generate-resources` phase, `mvn test` builds it too, and
a broken frontend fails the build:

```bash
mvn test
```

### Develop the frontend

Maven installs Node and npm inside `frontend/`, but for an edit and reload loop the Vite dev server
is faster, because it serves the sources with hot module replacement:

```bash
cd frontend
npm install
npm run dev
```

The dev server answers on <http://localhost:5173/app/> and proxies `/api` to
<http://localhost:8080>, so the API has to run in another terminal (`mvn spring-boot:run`). Nothing
of the dev server is packaged: `npm run build` writes the production bundle to the same
`target/classes/static/app` directory that Maven uses.
the service, links to the web UI and links to Swagger UI and to the raw OpenAPI JSON, so opening the
application in a browser is enough to find everything.

## REST API

Base path: `/api`. All payloads are JSON.

| Method   | Path                                        | Success | Purpose                                     |
|----------|---------------------------------------------|---------|---------------------------------------------|
| `POST`   | `/api/orders`                               | `201`   | Submit an order on an instrument            |
| `GET`    | `/api/orders/{id}`                          | `200`   | Fetch one order of any instrument           |
| `DELETE` | `/api/orders/{id}`                          | `200`   | Cancel a resting order                      |
| `GET`    | `/api/instruments`                          | `200`   | List active instruments with their stats    |
| `GET`    | `/api/instruments/search?q=&limit=`         | `200`   | Search the instrument catalogue             |
| `GET`    | `/api/instruments/{symbol}/orderbook`       | `200`   | Book snapshot of one instrument             |
| `GET`    | `/api/instruments/{symbol}/trades?limit=`   | `200`   | Recent trades of one instrument             |

### POST /api/orders

Submit an order on the instrument named by `symbol`, which is created on first use. Returns
`201 Created` with a `Location` header pointing to the new order, and a body with the order in its
final state plus the trades it generated, in execution order. Both the order and every trade carry the
symbol.

```bash
curl -s -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"BTC-USD","side":"BUY","type":"LIMIT","price":100.50,"quantity":10}'
```

```json
{
  "order": {
    "id": "6f1c1e2a-1c2b-4c3d-9e8f-0a1b2c3d4e5f",
    "symbol": "BTC-USD",
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

A MARKET order omits `price` and sweeps the book:

```bash
curl -s -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"BTC-USD","side":"SELL","type":"MARKET","quantity":5}'
```

The same symbol written differently addresses the very same book, so `" btc-usd "`, `"Btc-Usd"` and
`"BTC-USD"` are the same instrument:

```bash
curl -s -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"symbol":" btc-usd ","side":"BUY","type":"LIMIT","price":100.50,"quantity":10}'
```

### GET /api/orders/{id}

Fetch a single order of any instrument, including terminal ones (`FILLED` or `CANCELLED`). The symbol
is not needed: the global order index resolves the instrument that owns the id. `404 NOT_FOUND` when
the id is unknown, `400 INVALID_PARAMETER` when it is not a UUID.

```bash
curl -s http://localhost:8080/api/orders/6f1c1e2a-1c2b-4c3d-9e8f-0a1b2c3d4e5f
```

```json
{
  "id": "6f1c1e2a-1c2b-4c3d-9e8f-0a1b2c3d4e5f",
  "symbol": "BTC-USD",
  "side": "BUY",
  "type": "LIMIT",
  "price": 100.50,
  "quantity": 10,
  "remainingQuantity": 10,
  "filledQuantity": 0,
  "status": "NEW",
  "timestamp": "2026-10-04T09:00:00Z"
}
```

### DELETE /api/orders/{id}

Cancel an order that is still resting on the book of its own instrument. The request is routed to that
instrument only, so the books of the other instruments are untouched. Returns the cancelled order with
status `CANCELLED`.

`422 INVALID_ORDER_STATE` when the order cannot be cancelled: it is already `FILLED` or already
`CANCELLED`, or it is a MARKET order that never rests. `404 NOT_FOUND` when the id is unknown.

```bash
curl -s -X DELETE http://localhost:8080/api/orders/6f1c1e2a-1c2b-4c3d-9e8f-0a1b2c3d4e5f
```

### GET /api/instruments

Every active instrument with its stats, sorted by symbol. The list is empty until the first order is
accepted, because instruments are created on first use. `bestBid`, `bestAsk` and `lastPrice` are
omitted when that side is empty or when nothing has traded yet.

```bash
curl -s http://localhost:8080/api/instruments
```

```json
[
  {
    "symbol": "BTC-USD",
    "restingOrders": 3,
    "bestBid": 200.00,
    "bestAsk": 200.50,
    "lastPrice": 200.50
  },
  {
    "symbol": "ETH-USD",
    "restingOrders": 2,
    "bestBid": 205.00,
    "bestAsk": 210.00
  }
]
```

### GET /api/instruments/search

Search the catalogue of tradable instruments shipped with the service, in
[`src/main/resources/instruments.tsv`](src/main/resources/instruments.tsv): 47 Swiss SIX names
followed by the 503 S&P 500 constituents. This is the source the instrument dropdown of the web app
queries, so the UI and the API always suggest the same symbols.

The optional `q` query parameter is trimmed and matched case insensitively, in two ranks: the
instruments whose **symbol starts with** `q` come first, then the instruments whose **name contains**
`q`, each group sorted by symbol. A blank `q` (the default) returns the head of the catalogue, which
is how the dropdown opens on the Swiss names. The optional `limit` accepts 1 to 200 and defaults to
50.

Searching the catalogue never creates anything: an instrument only exists as a tradable book once an
order has been submitted for it, so a symbol returned here can still answer
`404 UNKNOWN_INSTRUMENT` on `/api/instruments/{symbol}/orderbook`.

```bash
curl -s 'http://localhost:8080/api/instruments/search?q=ubs&limit=10'
```

```json
[
  {"symbol": "UBSG", "name": "UBS Group", "market": "SIX"}
]
```

A query on the name works too, and ranks after the symbol matches:

```bash
curl -s 'http://localhost:8080/api/instruments/search?q=roche'
```

```json
[
  {"symbol": "ROG", "name": "Roche Holding", "market": "SIX"}
]
```

A query that matches nothing returns an empty array. A `limit` outside 1 to 200 is
`400 VALIDATION_ERROR`, a non numeric `limit` is `400 INVALID_PARAMETER`.

### GET /api/instruments/{symbol}/orderbook

Aggregated snapshot of one instrument, best price first on both sides. `bids` are ordered from the
highest price down, `asks` from the lowest price up, and each level is aggregated into a total
`quantity` and an `orderCount`. `bestBid`, `bestAsk`, `spread` (`bestAsk - bestBid`) and `lastPrice`
are omitted when they do not exist, that is when one side of the book is empty or nothing has traded.

```bash
curl -s http://localhost:8080/api/instruments/BTC-USD/orderbook
```

```json
{
  "bids": [
    {"price": 200.00, "quantity": 7, "orderCount": 2},
    {"price": 199.90, "quantity": 2, "orderCount": 1}
  ],
  "asks": [
    {"price": 200.50, "quantity": 1, "orderCount": 1},
    {"price": 201.00, "quantity": 5, "orderCount": 1}
  ],
  "bestBid": 200.00,
  "bestAsk": 200.50,
  "spread": 0.50,
  "lastPrice": 200.50
}
```

### GET /api/instruments/{symbol}/trades

Recent trades of one instrument, newest first. The trade tape of an instrument only ever contains its
own trades. The optional `limit` query parameter accepts 1 to 1000 and defaults to 50.

```bash
curl -s 'http://localhost:8080/api/instruments/BTC-USD/trades?limit=20'
```

```json
[
  {
    "id": "8a2b3c4d-5e6f-4a1b-8c9d-0e1f2a3b4c5d",
    "symbol": "BTC-USD",
    "buyOrderId": "1a2b3c4d-5e6f-4a1b-8c9d-0e1f2a3b4c5d",
    "sellOrderId": "2b3c4d5e-6f7a-4b1c-9d0e-1f2a3b4c5d6e",
    "price": 200.50,
    "quantity": 2,
    "timestamp": "2026-10-04T09:00:05Z"
  }
]
```

A `limit` outside 1 to 1000 is rejected with `400 VALIDATION_ERROR`, a non numeric `limit` with
`400 INVALID_PARAMETER`.

## Symbol rules

A symbol identifies a tradable instrument and is required everywhere.

- It is **trimmed** and **upper-cased** before use, so `" btc-usd "`, `"Btc-Usd"` and `"BTC-USD"` all
  address the very same book, and every response echoes the normalized form.
- After normalization it must match `[A-Z0-9][A-Z0-9._-]{0,19}`: one to twenty characters, starting
  with a letter or a digit, then letters, digits, dots, underscores and dashes.
- A **missing or malformed** symbol is `400 VALIDATION_ERROR`, with a `symbol` entry in `violations`.
- A **well formed but unknown** symbol on a query endpoint (`orderbook` or `trades`) is
  `404 UNKNOWN_INSTRUMENT`, because no order has ever created that book. `POST /api/orders` is the
  only way to bring an instrument into existence.

## Error handling

Every failing request returns the same JSON shape, produced by `GlobalExceptionHandler`:

```json
{
  "timestamp": "2026-10-04T09:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "code": "VALIDATION_ERROR",
  "message": "invalid request: quantity quantity must be greater than 0",
  "path": "/api/orders",
  "violations": [{"field": "quantity", "message": "quantity must be greater than 0"}]
}
```

`violations` is omitted when empty, because Jackson is configured with
`default-property-inclusion: non_null`. The `code` is stable and machine-readable:

| Code                   | Status | Raised when                                                           |
|------------------------|--------|-----------------------------------------------------------------------|
| `VALIDATION_ERROR`     | `400`  | Bean Validation failed on the body, on `symbol`, or on a parameter such as `limit` |
| `MALFORMED_JSON`       | `400`  | The body is not readable JSON, or an enum value is unknown            |
| `INVALID_PARAMETER`    | `400`  | A parameter has an invalid value, for example a non UUID order id    |
| `INVALID_ORDER`        | `400`  | A business rule was broken, for example a LIMIT order without a price |
| `NOT_FOUND`            | `404`  | Unknown order id or unknown path                                      |
| `UNKNOWN_INSTRUMENT`   | `404`  | A well formed symbol that has no book yet                             |
| `METHOD_NOT_ALLOWED`   | `405`  | Unsupported HTTP method on an existing path                           |
| `INVALID_ORDER_STATE`  | `422`  | A well formed request that breaks the order lifecycle, for example cancelling a filled order |
| `INTERNAL_ERROR`       | `500`  | Last resort handler, so internal failures keep the standard shape     |

The old single book endpoints (`GET /api/orderbook`, `GET /api/trades`) are gone and answer
`404 NOT_FOUND`: every query is now scoped to an instrument.

## Deploy

The API is packaged and run as a container, both from the JVM build and from a GraalVM native image.

### Native image

A GraalVM native image of the API is built and published to GitHub Container Registry (GHCR) by
[`.github/workflows/native-image.yml`](.github/workflows/native-image.yml). The workflow compiles the
native executable with `mvn -B -Pnative -DskipTests native:compile`, packages it with
[`Dockerfile.native`](Dockerfile.native) and pushes it to `ghcr.io/albertominetti/order-book`.

The image is published on every push to `main`, with the tags `native` (the latest build) and
`native-sha-<short-sha>` (one immutable tag per commit).

```bash
docker pull ghcr.io/albertominetti/order-book:native
docker run -p 8080:8080 ghcr.io/albertominetti/order-book:native
```

The API is then available on <http://localhost:8080>, with Swagger UI at
<http://localhost:8080/swagger-ui.html>.

Compared with a JVM image the native one is **much smaller**, because it carries no JRE, **starts
faster**, because it skips the JVM warm-up, and uses **less memory**, because the closed-world
analysis removes unused code and enables early class initialization.

## Tests

```bash
mvn test
```

The suite is organized in layers: pure unit tests for the engine, the registry and the catalogue,
end-to-end MockMvc tests for the REST API, then the landing page and the single page application
routing:
The suite is organized in three layers plus the two page tests:

- `MatchingEngineTest`: the matching core on a frozen clock. Resting orders, full match, partial fills,
  price-time priority (best price first, FIFO inside a level), market orders sweeping several levels
  and the discarded remainder, no cross when prices do not overlap, level aggregation and pruning,
  cancellation rules, the trade tape, input validation, and a concurrent submission test that checks
  no quantity is lost or duplicated.
- `MarketRegistryTest`: one engine per symbol created lazily and normalized, `engineOrThrow` on an
  unknown instrument, malformed symbols rejected without creating an instrument, sixteen threads
  racing on the first order of a new symbol, and the isolation guarantee that two symbols never match
  even at the same price while the same symbol does.
- `InstrumentCatalogTest`: the parser skips the header and the truncated lines, a blank query returns
  the default slice in catalogue order, symbol prefix matches rank before name matches and both are
  case insensitive, the limit caps the results, and the shipped catalogue really holds the 47 SIX
  names and the 503 S&P 500 constituents.
- `OrderApiIntegrationTest`: end-to-end MockMvc tests of every endpoint, including the `201` plus
  `Location` contract, symbol normalization, `GET`/`DELETE` on an order of any instrument, the per
  instrument book and trade tape, instrument listing, the full error matrix and instrument isolation.
- `InstrumentSearchApiTest`: end-to-end tests of `GET /api/instruments/search`: a query by symbol and
  a query by name, the default 50 instruments slice, the limit and its boundaries (`0` and `201` are
  `400`), an unknown query returning an empty array, the fact that the search creates no instrument
  and does not shadow `/api/instruments/{symbol}/...`, and its presence in the OpenAPI description.
- `MultiInstrumentApiTest`: two instruments interleaved end to end, symbol normalization, unknown
  instrument on both query endpoints, the OpenAPI description and Bean Validation on the payload.
- `HomePageTest`: the landing page at `/` answers `200` with an HTML body linking to the web app at
  `/app/`, to Swagger UI and to the OpenAPI JSON.
- `SpaRoutingTest`: `/app` and `/app/` forward to `/app/index.html`, and the entry document built by
  Maven from `frontend/` is really served, so the bundle is packaged.

The frontend build runs in the `generate-resources` phase, therefore every `mvn test` also runs
`npm install` and `vite build` before the Java tests.
- `HomePageTest`: the landing page at `/` answers `200` with an HTML body linking to Swagger UI and
  to the OpenAPI JSON.
- `UiPageTest`: the web UI at `/ui` answers `200` with an HTML body carrying the stable element ids
  its own script binds to, and with no external asset.

## License

MIT. See [LICENSE](LICENSE).

