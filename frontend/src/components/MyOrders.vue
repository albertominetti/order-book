<script setup>
const props = defineProps({
  orders: {
    type: Array,
    default: () => []
  },
  error: {
    type: String,
    default: ''
  },
  pendingId: {
    type: String,
    default: ''
  },
  activeSymbol: {
    type: String,
    required: true
  },
  onlyActiveSymbol: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['cancel', 'toggle-symbol-filter'])

const CANCELLABLE = ['NEW', 'PARTIALLY_FILLED']

function isCancellable(order) {
  return CANCELLABLE.indexOf(order.status) !== -1
}

function shortId(id) {
  return typeof id === 'string' ? id.slice(0, 8) : '-'
}

function value(value) {
  return value === null || value === undefined ? '-' : String(value)
}

function quantity(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number.toLocaleString('en-US') : '-'
}

function time(value) {
  if (!value) {
    return '-'
  }
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? String(value) : parsed.toLocaleTimeString('en-GB', { hour12: false })
}

function visibleOrders() {
  if (!props.onlyActiveSymbol) {
    return props.orders
  }
  return props.orders.filter((order) => order.symbol === props.activeSymbol)
}
</script>

<template>
  <section class="panel">
    <header>
      <h2>My orders</h2>
      <label class="toggle">
        <input
          type="checkbox"
          :checked="onlyActiveSymbol"
          @change="emit('toggle-symbol-filter')"
        >
        <span>{{ activeSymbol }} only</span>
      </label>
    </header>

    <div v-if="error" class="error" role="alert">{{ error }}</div>

    <p v-if="!error && visibleOrders().length === 0" class="empty">
      No order submitted from this browser yet.
    </p>

    <table v-else class="orders">
      <thead>
        <tr>
          <th scope="col">Time</th>
          <th scope="col">Symbol</th>
          <th scope="col">Side</th>
          <th scope="col">Price</th>
          <th scope="col">Open</th>
          <th scope="col">Status</th>
          <th scope="col"><span class="visually-hidden">Action</span></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="order in visibleOrders()" :key="order.id">
          <td>{{ time(order.timestamp) }}</td>
          <td :title="order.id">{{ order.symbol }}</td>
          <td :class="order.side === 'BUY' ? 'bid' : 'ask'">{{ shortId(order.id) }} {{ order.side }}</td>
          <td>{{ value(order.price) }}</td>
          <td>{{ quantity(order.remainingQuantity) }}</td>
          <td>
            <span class="status" :class="order.status.toLowerCase()">{{ order.status }}</span>
          </td>
          <td class="action">
            <button
              v-if="isCancellable(order)"
              type="button"
              class="cancel"
              :disabled="pendingId === order.id"
              @click="emit('cancel', order)"
            >
              {{ pendingId === order.id ? '...' : 'Cancel' }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>
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
  gap: 0.5rem;
}

h2 {
  margin: 0;
  font-size: 1rem;
}

.toggle {
  display: flex;
  align-items: center;
  gap: 0.3rem;
  font-size: 0.75rem;
  color: var(--text-muted);
}

.orders th {
  font-size: 0.7rem;
  font-weight: 500;
  color: var(--text-muted);
  text-align: right;
  padding-bottom: 0.15rem;
}

.orders td {
  padding: 0.2rem 0.3rem;
  font-size: 0.8rem;
  font-variant-numeric: tabular-nums;
  text-align: right;
  white-space: nowrap;
}

.orders td.bid {
  color: var(--bid);
}

.orders td.ask {
  color: var(--ask);
}

.orders td.action {
  text-align: right;
}

.status {
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.status.filled,
.status.cancelled {
  color: var(--text-muted);
}

.status.partially_filled {
  color: var(--accent);
}

.cancel {
  padding: 0.15rem 0.4rem;
  border: 1px solid var(--border);
  border-radius: 0.3rem;
  background: var(--surface);
  color: var(--danger);
  font-size: 0.75rem;
}

.empty {
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

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}
</style>