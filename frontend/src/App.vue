<script setup>
import { computed, ref, watch } from 'vue'
import {
  cancelOrder,
  describeError,
  getOrderBook,
  getRecentTrades,
  isUnknownInstrument,
  listInstruments,
  submitOrder,
  tradeLimit
} from './api.js'
import { usePoller } from './usePoller.js'
import { useStream } from './useStream.js'
import Instruments from './components/Instruments.vue'
import MyOrders from './components/MyOrders.vue'
import OrderBook from './components/OrderBook.vue'
import OrderForm from './components/OrderForm.vue'
import Trades from './components/Trades.vue'
import InstrumentSelect from './components/InstrumentSelect.vue'

const POLL_INTERVAL_MS = 1000
const DEFAULT_SYMBOL = 'UBSG'
const STORAGE_KEY = 'order-book.my-orders.v1'
const MAX_STORED_ORDERS = 50

const symbol = ref(DEFAULT_SYMBOL)

const book = ref(null)
const bookError = ref('')
const bookLoading = ref(false)

const trades = ref([])
const tradesError = ref('')

const instruments = ref([])
const instrumentsError = ref('')

const orders = ref(loadOrders())
const orderError = ref('')
const cancelError = ref('')
const submitting = ref(false)
const cancellingId = ref('')
const onlyActiveSymbol = ref(false)

function normalizeSymbol(raw) {
  return String(raw || '').trim().toUpperCase()
}

function loadOrders() {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY)
    const parsed = stored ? JSON.parse(stored) : []
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function storeOrders(current) {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(current.slice(0, MAX_STORED_ORDERS)))
  } catch {
    return
  }
}

watch(orders, (current) => {
  storeOrders(current)
}, { deep: true })

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
  bookError.value = ''
  tradesError.value = ''
  bookPoller.refresh()
  tradesPoller.refresh()
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
const instrumentsPoller = usePoller(loadInstruments, POLL_INTERVAL_MS)

/**
 * Live push channel over Server-Sent Events, the same payloads the pollers fetch.
 *
 * The pollers keep running, so they are the fallback and the badge in the top bar says which channel
 * is actually live. A `book` event also lands on a symbol whose book did not exist a moment ago, so
 * the panels never blink back to "loading" when a fresh instrument is selected.
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
  }
})

const channelLabel = computed(() => (connected.value ? 'live \u00b7 SSE' : 'polling'))

const channelTitle = computed(() =>
  connected.value
    ? 'Live updates are streamed over Server-Sent Events'
    : 'No event stream, falling back to polling once per second'
)

function remember(order) {
  const next = orders.value.filter((candidate) => candidate.id !== order.id)
  next.unshift(order)
  orders.value = next.slice(0, MAX_STORED_ORDERS)
}

async function onSubmit(order) {
  submitting.value = true
  orderError.value = ''
  try {
    const result = await submitOrder(order)
    remember(result.order)
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
    remember(await cancelOrder(order.id))
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
        <MyOrders
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
      <span>{{ connected ? 'Streaming with Server-Sent Events, polling once per second as a fallback.' : 'Polling every second.' }}</span>
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