<script setup>
import { computed, ref, watch } from 'vue'
import {
  cancelOrder,
  describeError,
  getOrderBook,
  getOrders,
  getRecentTrades,
  isUnknownInstrument,
  listInstruments,
  submitOrder,
  tradeLimit
} from './api.js'
import { usePoller } from './usePoller.js'
import { useStream } from './useStream.js'
import Instruments from './components/Instruments.vue'
import Orders from './components/Orders.vue'
import OrderBook from './components/OrderBook.vue'
import OrderForm from './components/OrderForm.vue'
import Trades from './components/Trades.vue'
import InstrumentSelect from './components/InstrumentSelect.vue'

const POLL_INTERVAL_MS = 1000
const DEFAULT_SYMBOL = 'UBSG'
const ORDERS_LIMIT = 50

const symbol = ref(DEFAULT_SYMBOL)

const book = ref(null)
const bookError = ref('')
const bookLoading = ref(false)

const trades = ref([])
const tradesError = ref('')

const instruments = ref([])
const instrumentsError = ref('')

const orders = ref([])
const orderError = ref('')
const cancelError = ref('')
const submitting = ref(false)
const cancellingId = ref('')
const onlyActiveSymbol = ref(false)

function normalizeSymbol(raw) {
  return String(raw || '').trim().toUpperCase()
}

function selectSymbol(next) {
  const normalized = normalizeSymbol(next)
  if (!normalized || normalized === symbol.value) {
  return
}
symbol.value = normalized
}

watch(symbol, () => {
  book.value = null
  trades.value = []
  orders.value = []
  bookError.value = ''
  tradesError.value = ''
  if (!connected.value) {
    bookPoller.refresh()
    tradesPoller.refresh()
    ordersPoller.refresh()
  }
})

async function loadBook() {
  bookLoading.value = book.value === null
  try {
    book.value = await getOrderBook(symbol.value)
    bookError.value = ''
  } catch (error) {
    book.value = null
    bookError.value = isUnknownInstrument(error) ? '' : describeError(error)
  } finally {
    bookLoading.value = false
  }
}

async function loadTrades() {
  try {
    trades.value = await getRecentTrades(symbol.value, tradeLimit)
    tradesError.value = ''
  } catch (error) {
    trades.value = []
    tradesError.value = isUnknownInstrument(error) ? '' : describeError(error)
  }
}

async function loadOrders() {
  try {
    orders.value = await getOrders(symbol.value, ORDERS_LIMIT)
    orderError.value = ''
  } catch (error) {
    orders.value = []
    orderError.value = describeError(error)
  }
}

async function loadInstruments() {
  try {
    instruments.value = await listInstruments()
    instrumentsError.value = ''
  } catch (error) {
    instruments.value = []
    instrumentsError.value = describeError(error)
  }
}

const bookPoller = usePoller(loadBook, POLL_INTERVAL_MS)
const tradesPoller = usePoller(loadTrades, POLL_INTERVAL_MS)
const ordersPoller = usePoller(loadOrders, POLL_INTERVAL_MS)
const instrumentsPoller = usePoller(loadInstruments, POLL_INTERVAL_MS)

/**
 * Live push channel over Server-Sent Events, the same payloads the pollers fetch.
 *
 * When SSE is connected, it is the single source of truth for book, orders and trades.
 * The REST pollers are stopped. When SSE disconnects, the app falls back to polling.
 */
const { connected } = useStream(symbol, {
  onBook: (payload) => {
    if (!payload) {
      return
    }
    book.value = payload
    bookError.value = ''
    bookLoading.value = false
  },
  onOrders: (payload) => {
    if (!Array.isArray(payload)) {
      return
    }
    orders.value = payload
    orderError.value = ''
  },
  onTrades: (payload) => {
    if (!Array.isArray(payload)) {
      return
    }
    trades.value = payload
    tradesError.value = ''
  },
  onInstruments: (payload) => {
    if (!Array.isArray(payload)) {
      return
    }
    instruments.value = payload
    instrumentsError.value = ''
  },
  onStateChange: (isConnected) => {
    if (isConnected) {
      bookPoller.stop()
      tradesPoller.stop()
      ordersPoller.stop()
      book.value = null
      trades.value = []
      orders.value = []
      bookError.value = ''
      tradesError.value = ''
      bookLoading.value = book.value === null
    } else {
      bookPoller.start()
      tradesPoller.start()
      ordersPoller.start()
      bookPoller.refresh()
      tradesPoller.refresh()
      ordersPoller.refresh()
    }
  }
})

const channelLabel = computed(() => (connected.value ? 'live · SSE' : 'polling fallback'))

const channelTitle = computed(() =>
  connected.value
    ? 'Live updates are streamed over Server-Sent Events'
    : 'No event stream, falling back to polling once per second'
)

async function onSubmit(order) {
  submitting.value = true
  orderError.value = ''
  try {
    await submitOrder(order)
    refreshAll()
  } catch (error) {
    orderError.value = describeError(error)
  } finally {
    submitting.value = false
  }
}

async function onCancel(order) {
  cancellingId.value = order.id
  cancelError.value = ''
  try {
    await cancelOrder(order.id)
    refreshAll()
  } catch (error) {
    cancelError.value = describeError(error)
  } finally {
    cancellingId.value = ''
  }
}

function toggleSymbolFilter() {
  onlyActiveSymbol.value = !onlyActiveSymbol.value
}

function refreshAll() {
  bookPoller.refresh()
  tradesPoller.refresh()
  ordersPoller.refresh()
  instrumentsPoller.refresh()
}
</script>

<template>
  <div class="page">
    <header class="topbar">
      <div class="brand">
        <h1>Order Book</h1>
        <span class="tagline">Vue 3 client of the /api REST endpoints</span>
      </div>

      <div class="symbol-picker">
        <span
          class="channel-badge"
          :class="connected ? 'sse' : 'polling'"
          :title="channelTitle"
          role="status"
          aria-live="polite"
        >{{ channelLabel }}</span>
        <InstrumentSelect v-model="symbol" />
        <button type="button" class="refresh" @click="refreshAll">Refresh</button>
      </div>
    </header>

    <p v-if="orderError" class="banner" role="alert">{{ orderError }}</p>

    <main class="layout">
      <div class="column">
        <OrderForm
          class="card"
          :symbol="symbol"
          :busy="submitting"
          @submit="onSubmit"
        />
        <Instruments
          class="card"
          :instruments="instruments"
          :active-symbol="symbol"
          :error="instrumentsError"
          @select="selectSymbol"
        />
      </div>

      <div class="column">
        <OrderBook
          class="card"
          :book="book"
          :error="bookError"
          :loading="bookLoading"
        />
        <Orders
          class="card"
          :orders="orders"
          :error="cancelError"
          :pending-id="cancellingId"
          :active-symbol="symbol"
          :only-active-symbol="onlyActiveSymbol"
          @cancel="onCancel"
          @toggle-symbol-filter="toggleSymbolFilter"
        />
      </div>

      <div class="column">
        <Trades
          class="card"
          :trades="trades"
          :error="tradesError"
          :limit="tradeLimit"
        />
      </div>
    </main>

    <footer class="footer">
      <span>{{ connected ? 'Streaming with Server-Sent Events, REST polling used only as a fallback.' : 'Polling every second as fallback.' }}</span>
      <a href="/">Landing page</a>
      <a href="/swagger-ui.html">Swagger UI</a>
    </footer>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 0.85rem;
  max-width: 78rem;
  margin: 0 auto;
  padding: 1.25rem;
}

.topbar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.9rem 1rem;
  border: 1px solid var(--border);
  border-radius: 0.6rem;
  background: var(--surface);
}

h1 {
  margin: 0;
  font-size: 1.3rem;
}

.tagline {
  font-size: 0.75rem;
  color: var(--text-muted);
}

.symbol-picker {
  display: flex;
  align-items: flex-end;
  gap: 0.6rem;
}

.channel-badge {
  padding: 0.25rem 0.55rem;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--surface-muted);
  color: var(--text-muted);
  font-size: 0.7rem;
  font-weight: 600;
  letter-spacing: 0.05em;
  white-space: nowrap;
  height: fit-content;
}

.channel-badge.sse {
  border-color: var(--bid);
  background: var(--bid-soft);
  color: var(--bid);
}

.channel-badge.polling {
  border-color: var(--accent);
  color: var(--accent);
}

.refresh {
  padding: 0.35rem 0.6rem;
  border: 1px solid var(--border);
  border-radius: 0.35rem;
  background: var(--surface);
  color: inherit;
  font-size: 0.8rem;
  height: fit-content;
}

.banner {
  margin: 0;
  padding: 0.55rem 0.75rem;
  border: 1px solid var(--danger);
  border-radius: 0.4rem;
  color: var(--danger);
  font-size: 0.85rem;
}

.layout {
  display: grid;
  grid-template-columns: minmax(15rem, 1fr) minmax(20rem, 1.6fr) minmax(14rem, 1fr);
  gap: 0.85rem;
  align-items: start;
}

.column {
  display: flex;
  flex-direction: column;
  gap: 0.85rem;
  min-width: 0;
}

.card {
  padding: 0.85rem;
  border: 1px solid var(--border);
  border-radius: 0.6rem;
  background: var(--surface);
  min-width: 0;
}

.footer {
  display: flex;
  gap: 1rem;
  font-size: 0.8rem;
  color: var(--text-muted);
}

.footer a {
  color: var(--accent);
}

@media (max-width: 1100px) {
  .layout {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>