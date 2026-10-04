<script setup>
defineProps({
  instruments: {
    type: Array,
    default: () => []
  },
  activeSymbol: {
    type: String,
    required: true
  },
  error: {
    type: String,
    default: ''
  },
  loading: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['select'])

function value(value) {
  return value === null || value === undefined ? '-' : String(value)
}
</script>

<template>
  <section class="panel">
    <header>
      <h2>Instruments</h2>
      <span v-if="loading && instruments.length === 0" class="badge">loading</span>
      <span v-else class="badge">{{ instruments.length }} active</span>
    </header>

    <div v-if="error" class="error" role="alert">{{ error }}</div>

    <p v-if="!error && instruments.length === 0" class="empty">
      No instrument yet. An instrument appears as soon as its first order arrives.
    </p>

    <ul v-else class="list">
      <li v-for="instrument in instruments" :key="instrument.symbol">
        <button
          type="button"
          class="row"
          :class="{ active: instrument.symbol === activeSymbol }"
          :aria-current="instrument.symbol === activeSymbol ? 'true' : undefined"
          @click="emit('select', instrument.symbol)"
        >
          <span class="symbol">{{ instrument.symbol }}</span>
          <span class="stats">
            <span class="bid">{{ value(instrument.bestBid) }}</span>
            <span class="ask">{{ value(instrument.bestAsk) }}</span>
          </span>
          <span class="resting">{{ instrument.restingOrders }} resting</span>
        </button>
      </li>
    </ul>
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

.list {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.row {
  display: grid;
  grid-template-columns: minmax(5rem, 1fr) minmax(7rem, 1.4fr) auto;
  gap: 0.5rem;
  align-items: center;
  width: 100%;
  padding: 0.35rem 0.45rem;
  border: 1px solid var(--border);
  border-radius: 0.35rem;
  background: var(--surface);
  color: inherit;
  text-align: left;
}

.row:hover {
  border-color: var(--accent);
}

.row.active {
  border-color: var(--accent);
  box-shadow: inset 2px 0 0 var(--accent);
}

.symbol {
  font-weight: 600;
}

.stats {
  display: flex;
  gap: 0.6rem;
  font-variant-numeric: tabular-nums;
  font-size: 0.85rem;
}

.stats .bid {
  color: var(--bid);
}

.stats .ask {
  color: var(--ask);
}

.resting {
  font-size: 0.75rem;
  color: var(--text-muted);
  text-align: right;
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