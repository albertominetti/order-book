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
      <h2>Orders</h2>
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
      No orders on this instrument yet.
    </p>

    <div v-else class="table-scroll">
      <table class="orders">
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
  flex-wrap: wrap;
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

/*
 * The table is never wider than the panel: when the columns do not fit, the container scrolls
 * sideways instead of letting the Cancel button overflow the viewport.
 */
.table-scroll {
  width: 100%;
  max-width: 100%;
  min-width: 0;
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
}

.orders {
  width: 100%;
  min-width: 30rem;
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

/*
 * Phones: the table collapses into one card per order, with the Cancel button on its own full
 * width row at the bottom, so it is always fully visible and tappable, whatever the column count.
 */
@media (max-width: 600px) {
  .orders,
  .orders tbody,
  .orders tr,
  .orders td {
    display: block;
    width: auto;
  }

  .orders {
    min-width: 0;
  }

  .orders thead {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
    white-space: nowrap;
  }

  .orders tr {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, auto);
    grid-template-areas:
      "symbol status"
      "side   price"
      "time   open"
      "action action";
    gap: 0.15rem 0.6rem;
    align-items: baseline;
    margin-bottom: 0.5rem;
    padding: 0.5rem 0.6rem;
    border: 1px solid var(--border);
    border-radius: 0.45rem;
    background: var(--surface-muted);
  }

  .orders tr:last-child {
    margin-bottom: 0;
  }

  .orders td {
    padding: 0;
    font-size: 0.8rem;
    text-align: left;
    white-space: normal;
    overflow-wrap: anywhere;
  }

  .orders td:nth-child(1) {
    grid-area: time;
    font-size: 0.75rem;
    color: var(--text-muted);
  }

  .orders td:nth-child(2) {
    grid-area: symbol;
    font-size: 0.9rem;
    font-weight: 600;
  }

  .orders td:nth-child(3) {
    grid-area: side;
  }

  .orders td:nth-child(4) {
    grid-area: price;
    justify-self: end;
  }

  .orders td:nth-child(5) {
    grid-area: open;
    justify-self: end;
  }

  .orders td:nth-child(6) {
    grid-area: status;
    justify-self: end;
    text-align: right;
  }

  .orders td.action {
    grid-area: action;
    margin-top: 0.4rem;
    text-align: left;
  }

  .orders td.action .cancel {
    width: 100%;
    min-height: 2.4rem;
    padding: 0.5rem 0.6rem;
    font-size: 0.85rem;
  }
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
