<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  symbol: {
    type: String,
    required: true
  },
  busy: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['submit'])

const sides = ['BUY', 'SELL']
const types = ['LIMIT', 'MARKET']

const side = ref('BUY')
const type = ref('LIMIT')
const price = ref('')
const quantity = ref('')
const localError = ref('')

const priceRequired = computed(() => type.value === 'LIMIT')

function toPositiveNumber(raw) {
  const value = Number(raw)
  if (raw === '' || raw === null || raw === undefined || !Number.isFinite(value) || value <= 0) {
    return null
  }
  return value
}

function submit() {
  localError.value = ''

  const amount = toPositiveNumber(quantity.value)
  if (amount === null) {
    localError.value = 'Quantity is required and must be greater than 0.'
    return
  }

  const order = {
    symbol: props.symbol,
    side: side.value,
    type: type.value,
    quantity: amount
  }

  if (priceRequired.value) {
    const limit = toPositiveNumber(price.value)
    if (limit === null) {
      localError.value = 'Price is required for a LIMIT order and must be greater than 0.'
      return
    }
    order.price = limit
  }

  emit('submit', order)
}

watch(type, () => {
  localError.value = ''
})
</script>

<template>
  <form class="panel" @submit.prevent="submit">
    <h2>Order entry</h2>

    <label class="field">
      <span>Side</span>
      <select v-model="side">
        <option v-for="value in sides" :key="value" :value="value">{{ value }}</option>
      </select>
    </label>

    <label class="field">
      <span>Type</span>
      <select v-model="type">
        <option v-for="value in types" :key="value" :value="value">{{ value }}</option>
      </select>
    </label>

    <label class="field">
      <span>Price</span>
      <input
        v-model="price"
        type="number"
        min="0"
        step="any"
        inputmode="decimal"
        :required="priceRequired"
        :disabled="!priceRequired"
        :placeholder="priceRequired ? '100.00' : 'not needed for MARKET'"
      >
    </label>

    <label class="field">
      <span>Quantity</span>
      <input
        v-model="quantity"
        type="number"
        min="0"
        step="any"
        inputmode="decimal"
        required
        placeholder="10"
      >
    </label>

    <button
      class="submit"
        :class="side === 'BUY' ? 'buy' : 'sell'"
        type="submit"
        :disabled="busy"
      >
        {{ busy ? 'Sending...' : side + ' ' + type }}
    </button>

    <p v-if="localError" class="error" role="alert">{{ localError }}</p>
  </form>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
}

h2 {
  margin: 0 0 0.2rem;
  font-size: 1rem;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  font-size: 0.8rem;
  color: var(--text-muted);
}

input,
select {
  padding: 0.4rem 0.5rem;
  border: 1px solid var(--border);
  border-radius: 0.35rem;
  background: var(--surface);
}

input:disabled {
  background: var(--surface-muted);
  color: var(--text-muted);
}

.submit {
  margin-top: 0.2rem;
  padding: 0.5rem;
  border: 1px solid transparent;
  border-radius: 0.35rem;
  color: #ffffff;
  font-weight: 600;
}

.submit.buy {
  background: var(--bid);
}

.submit.sell {
  background: var(--ask);
}

.error {
  margin: 0;
  color: var(--danger);
  font-size: 0.8rem;
}
</style>