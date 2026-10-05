<script setup>
import { computed } from 'vue'

const props = defineProps({
  book: {
    type: Object,
    default: null
  },
  error: {
    type: String,
    default: ''
  },
  loading: {
    type: Boolean,
    default: false
  },
  rows: {
    type: Number,
    default: 12
  }
})

const BIDS = 'bids'
const ASKS = 'asks'

function byPriceDescending(left, right) {
  return Number(right.price) - Number(left.price)
}

function byPriceAscending(left, right) {
  return Number(left.price) - Number(right.price)
}

const bids = computed(() =>
  props.book && Array.isArray(props.book.bids) ? [...props.book.bids].sort(byPriceDescending) : []
)

const asks = computed(() =>
  props.book && Array.isArray(props.book.asks) ? [...props.book.asks].sort(byPriceAscending) : []
)

const topBids = computed(() => bids.value.slice(0, props.rows))
const topAsks = computed(() => asks.value.slice(0, props.rows))

const maxQuantity = computed(() => {
  const levels = [...topBids.value, ...topAsks.value]
  return levels.reduce((max, level) => Math.max(max, Number(level.quantity) || 0), 0)
})

function depth(level) {
  const max = maxQuantity.value
  if (!max) {
    return '0%'
  }
  const ratio = (Number(level.quantity) || 0) / max
  return Math.max(2, Math.round(ratio * 100)) + '%'
}

function price(value) {
  return value === null || value === undefined ? '-' : String(value)
}

function quantity(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number.toLocaleString('en-US') : '-'
}
</script>

<template>
  <section class="panel">
    <header>
      <h2>Order book</h2>
      <span v-if="loading && !book" class="badge">loading</span>
    </header>

    <div v-if="error" class="error" role="alert">{{ error }}</div>

    <dl class="summary">
      <div>
        <dt>Best bid</dt>
        <dd class="bid">{{ price(book && book.bestBid) }}</dd>
      </div>
      <div>
        <dt>Best ask</dt>
        <dd class="ask">{{ price(book && book.bestAsk) }}</dd>
      </div>
      <div>
        <dt>Spread</dt>
        <dd>{{ price(book && book.spread) }}</dd>
      </div>
      <div>
        <dt>Last price</dt>
        <dd>{{ price(book && book.lastPrice) }}</dd>
      </div>
    </dl>

    <p v-if="!error && bids.length === 0 && asks.length === 0" class="empty">
      No resting order on this instrument yet. Submit a LIMIT order to create a level.
    </p>

    <div v-else class="levels">
      <table v-if="asks.length" class="levels-table">
        <caption class="caption ask-caption">Asks (lowest first)</caption>
        <thead>
          <tr>
            <th scope="col">Price</th>
            <th scope="col">Quantity</th>
            <th scope="col">Orders</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="level in topAsks" :key="'a' + level.price" class="ask">
            <td class="depth" :style="{ backgroundSize: depth(level) + ' 100%' }">
              {{ price(level.price) }}
            </td>
            <td>{{ quantity(level.quantity) }}</td>
            <td>{{ level.orderCount }}</td>
          </tr>
        </tbody>
      </table>

      <p v-if="asks.length === 0" class="half-empty">No ask on the book.</p>

      <table v-if="bids.length" class="levels-table">
        <caption class="caption bid-caption">Bids (highest first)</caption>
        <thead>
          <tr>
            <th scope="col">Price</th>
            <th scope="col">Quantity</th>
            <th scope="col">Orders</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="level in topBids" :key="'b' + level.price" class="bid">
            <td class="depth" :style="{ backgroundSize: depth(level) + ' 100%' }">
              {{ price(level.price) }}
            </td>
            <td>{{ quantity(level.quantity) }}</td>
            <td>{{ level.orderCount }}</td>
          </tr>
        </tbody>
      </table>

      <p v-if="bids.length === 0" class="half-empty">No bid on the book.</p>
    </div>
  </section>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
}

header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

h2 {
  margin: 0;
  font-size: 1rem;
}

.badge {
  font-size: 0.7rem;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(6rem, 1fr));
  gap: 0.4rem;
  margin: 0;
}

.summary dt {
  font-size: 0.7rem;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.summary dd {
  margin: 0;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.summary dd.bid {
  color: var(--bid);
}

.summary dd.ask {
  color: var(--ask);
}

.levels {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.caption {
  padding-bottom: 0.25rem;
  font-size: 0.7rem;
  text-align: left;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--text-muted);
}

.levels-table th {
  font-size: 0.7rem;
  font-weight: 500;
  color: var(--text-muted);
  text-align: right;
  padding-bottom: 0.15rem;
}

.levels-table td {
  padding: 0.15rem 0.3rem;
  font-variant-numeric: tabular-nums;
  text-align: right;
  font-size: 0.85rem;
}

.levels-table .depth {
  background-image: linear-gradient(var(--level-soft), var(--level-soft));
  background-repeat: no-repeat;
  border-radius: 0.2rem;
}

.levels-table tr.bid {
  --level-soft: var(--bid-soft);
  --level-color: var(--bid);
  color: var(--bid);
}

.levels-table tr.ask {
  --level-soft: var(--ask-soft);
  --level-color: var(--ask);
  color: var(--ask);
}

.empty,
.half-empty {
  margin: 0;
  font-size: 0.8rem;
  color: var(--text-muted);
}

.error {
  margin: 0;
  padding: 0.4rem 0.5rem;
  border: 1px solid var(--border);
  border-radius: 0.35rem;
  color: var(--danger);
  font-size: 0.8rem;
}
</style>
