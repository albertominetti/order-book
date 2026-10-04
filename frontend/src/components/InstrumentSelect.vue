<script setup>
import { computed, ref, watch } from 'vue'
import { INSTRUMENTS } from '../data/instruments.js'

const props = defineProps({
  modelValue: {
    type: String,
    default: 'UBSG'
  }
})

const emit = defineEmits(['update:modelValue'])

const searchTerm = ref(props.modelValue)
const isOpen = ref(false)
const highlightedIndex = ref(-1)

const normalizedSymbolPattern = /^[A-Za-z0-9][A-Za-z0-9._-]{0,19}$/

function normalizeSymbol(raw) {
  return String(raw || '').trim().toUpperCase()
}

const filteredInstruments = computed(() => {
  const query = searchTerm.value.trim()
  let results = []

  if (!query) {
    results = INSTRUMENTS.slice(0, 50)
  } else {
    const normalizedQuery = query.toUpperCase()
    const matches = []

    for (let i = 0; i < INSTRUMENTS.length && matches.length < 50; i++) {
      const instrument = INSTRUMENTS[i]
      const symbolPrefixMatch = instrument.symbol.toUpperCase().startsWith(normalizedQuery)
      const nameSubstringMatch = !symbolPrefixMatch && instrument.name.toUpperCase().includes(normalizedQuery)
      if (symbolPrefixMatch || nameSubstringMatch) {
        matches.push({ ...instrument, priority: symbolPrefixMatch ? 0 : 1 })
      }
    }

    matches.sort((a, b) => {
      if (a.priority !== b.priority) return a.priority - b.priority
      if (a.symbol !== b.symbol) return a.symbol.localeCompare(b.symbol)
      return a.name.localeCompare(b.name)
    })

    results = matches
  }

  const normalizedQuery = query.trim().toUpperCase()
  const isValidFreeForm = normalizedQuery && normalizedSymbolPattern.test(normalizedQuery)
  const isInList = isValidFreeForm && results.some(item => item.symbol === normalizedQuery)

  if (isValidFreeForm && !isInList) {
    results = [{ symbol: normalizedQuery, name: 'Custom', market: 'Custom' }, ...results]
  }

  return results.slice(0, 50)
})

function selectInstrument(symbol) {
  const normalized = normalizeSymbol(symbol)
  if (!normalized) return

  searchTerm.value = normalized
  emit('update:modelValue', normalized)
  isOpen.value = false
  highlightedIndex.value = -1
}

function handleInput() {
  isOpen.value = true
  highlightedIndex.value = -1
  emit('update:modelValue', normalizeSymbol(searchTerm.value) || searchTerm.value)
}

function handleFocus() {
  isOpen.value = true
  highlightedIndex.value = -1
}

function handleBlur() {
  setTimeout(() => {
    isOpen.value = false
    highlightedIndex.value = -1
    searchTerm.value = props.modelValue
  }, 200)
}

function handleKeydown(event) {
  if (!isOpen.value || filteredInstruments.value.length === 0) {
    if (event.key === 'ArrowDown' || event.key === 'Enter') {
      isOpen.value = true
    }
    return
  }

  if (event.key === 'ArrowDown') {
    event.preventDefault()
    highlightedIndex.value = (highlightedIndex.value + 1) % filteredInstruments.value.length
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    highlightedIndex.value = highlightedIndex.value <= 0
      ? filteredInstruments.value.length - 1
      : highlightedIndex.value - 1
  } else if (event.key === 'Enter') {
    event.preventDefault()
    if (highlightedIndex.value >= 0 && highlightedIndex.value < filteredInstruments.value.length) {
      selectInstrument(filteredInstruments.value[highlightedIndex.value].symbol)
    } else {
      selectInstrument(searchTerm.value)
    }
  } else if (event.key === 'Escape') {
    event.preventDefault()
    isOpen.value = false
    highlightedIndex.value = -1
    searchTerm.value = props.modelValue
  }
}

watch(() => props.modelValue, (newValue) => {
  if (normalizeSymbol(newValue) !== normalizeSymbol(searchTerm.value)) {
    searchTerm.value = newValue
  }
})
</script>

<template>
  <div class="instrument-select">
    <label class="symbol-field">
      <span>Instrument</span>
      <input
        v-model="searchTerm"
        type="text"
        maxlength="20"
        spellcheck="false"
        autocomplete="off"
        @input="handleInput"
        @focus="handleFocus"
        @blur="handleBlur"
        @keydown="handleKeydown"
      >
    </label>
    <div v-if="isOpen && filteredInstruments.length > 0" class="dropdown">
      <div
        v-for="(instrument, index) in filteredInstruments"
        :key="instrument.symbol + '-' + index"
        class="dropdown-item"
        :class="{ highlighted: index === highlightedIndex }"
        @mousedown.prevent="selectInstrument(instrument.symbol)"
        @mouseenter="highlightedIndex = index"
      >
        <span class="symbol">{{ instrument.symbol }}</span>
        <span class="name">{{ instrument.name }}</span>
        <span class="market">{{ instrument.market }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.instrument-select {
  position: relative;
  display: flex;
  align-items: flex-end;
}

.symbol-field {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  font-size: 0.7rem;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.symbol-field input {
  width: 12rem;
  padding: 0.35rem 0.5rem;
  border: 1px solid var(--border);
  border-radius: 0.35rem;
  background: var(--surface);
  font-size: 0.95rem;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.dropdown {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  z-index: 1000;
  margin-top: 0.25rem;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 0.35rem;
  max-height: 20rem;
  overflow-y: auto;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
}

.dropdown-item {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 0.5rem;
  align-items: center;
  padding: 0.4rem 0.5rem;
  cursor: pointer;
  border-bottom: 1px solid var(--border);
}

.dropdown-item:last-child {
  border-bottom: none;
}

.dropdown-item.highlighted,
.dropdown-item:hover {
  background-color: var(--border);
}

.symbol {
  font-weight: 600;
  font-size: 0.9rem;
}

.name {
  font-size: 0.8rem;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.market {
  font-size: 0.7rem;
  color: var(--text-muted);
  text-transform: uppercase;
}
</style>