<script setup>
import { computed } from 'vue'

const props = defineProps({
  trades: {
    type: Array,
    default: () => []
  },
  error: {
    type: String,
    default: ''
  },
  limit: {
    type: Number,
    default: 20
  }
})

const rows = computed(() => (Array.isArray(props.trades) ? props.trades.slice(0, props.limit) : []))

function price(value) {
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
  return Number.isNaN(parsed.getTime())
    ? String(value)
    : parsed.toLocaleTimeString('en-GB', { hour12: false })
}
</script>

<template>
  <section class="panel">
    <header>
      <h2>Recent trades</h2>
      <span class="badge">last {{ limit }}</span>
    </header>

    <div v-if="error" class="error" role="alert">{{ error }}</div>

    <p v-if="!error && rows.length === 0" class="empty">
      No trade on this instrument yet.
    </p>

    <table v-else class="trades">
      <thead>
        <tr>
          <th scope="col">Time</th>
          <th scope="col">Price</th>
          <th scope="col">Quantity</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="trade in rows" :key="trade.id">
          <td>{{ time(trade.timestamp) }}</td>
          <td>{{ price(trade.price) }}</td>
          <td>{{ quantity(trade.quantity) }}</td>
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

.trades th {
  font-size: 0.7rem;
  font-weight: 500;
  color: var(--text-muted);
  text-align: right;
  padding-bottom: 0.15rem;
}

.trades td {
  padding: 0.15rem 0.3rem;
  font-size: 0.85rem;
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.trades tbody tr:first-child td {
  font-weight: 600;
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
</style>
