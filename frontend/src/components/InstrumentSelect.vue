<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { describeError, isAborted, searchInstruments, searchLimit } from '../api.js'

const DEBOUNCE_MS = 200
const CLOSE_DELAY_MS = 150

const SYMBOL_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,19}$/

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
const results = ref([])
const loading = ref(false)
const errorMessage = ref('')
const searched = ref(false)

let debounceTimer = 0
let closeTimer = 0
let inFlight = null
let latestRequest = 0

function normalizeSymbol(raw) {
  return String(raw || '').trim().toUpperCase()
}

/** Drops the pending debounce and cancels the search that is still in flight. */
function cancelPending() {
  if (debounceTimer) {
    window.clearTimeout(debounceTimer)
    debounceTimer = 0
  }
  if (inFlight) {
    inFlight.abort()
    inFlight = null
  }
}

/**
 * Asks the backend catalogue for the instruments matching the term.
 *
 * <p>Only one request is ever in flight: the previous one is aborted and every response whose id is
 * not the latest is ignored, so a slow answer can never overwrite a newer one.</p>
 */
function runSearch(term) {
  cancelPending()

  const requestId = latestRequest + 1
  latestRequest = requestId
  const controller = new AbortController()
  inFlight = controller
  loading.value = true
  errorMessage.value = ''

  searchInstruments(term, searchLimit, { signal: controller.signal })
    .then((payload) => {
      if (requestId !== latestRequest) {
        return
      }
      results.value = Array.isArray(payload) ? payload : []
      searched.value = true
    })
    .catch((error) => {
      if (requestId !== latestRequest || isAborted(error)) {
        return
      }
      results.value = []
      searched.value = true
      errorMessage.value = describeError(error)
    })
    .finally(() => {
      if (requestId !== latestRequest) {
        return
      }
      loading.value = false
      if (inFlight === controller) {
        inFlight = null
      }
    })
}

function scheduleSearch() {
  cancelPending()
  const term = searchTerm.value
  debounceTimer = window.setTimeout(() => {
    debounceTimer = 0
    runSearch(term)
  }, DEBOUNCE_MS)
}

/** The rows to draw: the catalogue hits, plus a free form row for a typed ticker nobody lists. */
const rows = computed(() => {
  const typed = normalizeSymbol(searchTerm.value)
  if (!typed || !SYMBOL_PATTERN.test(typed)) {
    return results.value
  }
  const known = results.value.some((instrument) => normalizeSymbol(instrument.symbol) === typed)
  if (known) {
    return results.value
  }
  return [{ symbol: typed, name: 'Use ' + typed, market: 'free form', custom: true }, ...results.value]
})

const statusMessage = computed(() => {
  if (loading.value) {
    return 'Searching the instrument catalogue...'
  }
  if (errorMessage.value) {
    return errorMessage.value
  }
  if (rows.value.length === 0) {
    return 'No instrument matches "' + searchTerm.value.trim() + '".'
  }
  return ''
})

function selectSymbol(symbol) {
  const normalized = normalizeSymbol(symbol)
  if (!normalized) {
    return
  }
  cancelPending()
  searchTerm.value = normalized
  emit('update:modelValue', normalized)
  isOpen.value = false
  highlightedIndex.value = -1
}

function handleInput(event) {
  searchTerm.value = event.target.value
  isOpen.value = true
  highlightedIndex.value = -1
  scheduleSearch()
}

function handleFocus() {
  if (closeTimer) {
    window.clearTimeout(closeTimer)
    closeTimer = 0
  }
  isOpen.value = true
  highlightedIndex.value = -1
  if (!searched.value) {
    runSearch(searchTerm.value)
  }
}

function handleBlur() {
  closeTimer = window.setTimeout(() => {
    closeTimer = 0
    isOpen.value = false
    highlightedIndex.value = -1
    searchTerm.value = props.modelValue
    cancelPending()
  }, CLOSE_DELAY_MS)
}

function moveHighlight(step) {
  const size = rows.value.length
  if (size === 0) {
    return
  }
  const next = highlightedIndex.value + step
  highlightedIndex.value = next < 0 ? size - 1 : next % size
}

function handleKeydown(event) {
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    if (!isOpen.value) {
      handleFocus()
    }
    moveHighlight(1)
    return
  }

  if (event.key === 'ArrowUp') {
    event.preventDefault()
    if (!isOpen.value) {
      handleFocus()
    }
    moveHighlight(-1)
    return
  }

  if (event.key === 'Enter') {
    event.preventDefault()
    const highlighted = rows.value[highlightedIndex.value]
    selectSymbol(highlighted ? highlighted.symbol : searchTerm.value)
    return
  }

  if (event.key === 'Escape') {
    event.preventDefault()
    isOpen.value = false
    highlightedIndex.value = -1
    searchTerm.value = props.modelValue
    cancelPending()
  }
}

watch(() => props.modelValue, (value) => {
  if (normalizeSymbol(value) !== normalizeSymbol(searchTerm.value)) {
    searchTerm.value = value
  }
})

watch(rows, (list) => {
  if (highlightedIndex.value >= list.length) {
    highlightedIndex.value = list.length - 1
  }
})

onBeforeUnmount(() => {
  if (closeTimer) {
    window.clearTimeout(closeTimer)
  }
  cancelPending()
})
</script>

<template>
  <div class="instrument-select">
    <label class="symbol-field">
      <span>Instrument</span>
      <input
        :value="searchTerm"
        type="text"
        maxlength="40"
        spellcheck="false"
        autocomplete="off"
        role="combobox"
        aria-autocomplete="list"
        :aria-expanded="isOpen"
        aria-controls="instrument-search-results"
        @input="handleInput"
        @focus="handleFocus"
        @blur="handleBlur"
        @keydown="handleKeydown"
      >
    </label>

    <div
      v-if="isOpen"
      id="instrument-search-results"
      class="dropdown"
      role="listbox"
      aria-live="polite"
    >
      <p v-if="statusMessage" class="status" :class="{ error: errorMessage }">
        {{ statusMessage }}
      </p>

      <div
        v-for="(instrument, index) in rows"
        :key="instrument.symbol + '-' + index"
        class="dropdown-item"
        :class="{ highlighted: index === highlightedIndex }"
        role="option"
        :aria-selected="index === highlightedIndex"
        @mousedown.prevent="selectSymbol(instrument.symbol)"
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
  max-width: 100%;
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
  overscroll-behavior: contain;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
}

.status {
  margin: 0;
  padding: 0.45rem 0.5rem;
  font-size: 0.75rem;
  color: var(--text-muted);
  border-bottom: 1px solid var(--border);
}

.status.error {
  color: var(--danger);
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

@media (max-width: 600px) {
  .symbol-field,
  .symbol-field input {
    width: 100%;
  }

  .dropdown {
    right: auto;
    width: min(22rem, calc(100vw - 2.5rem));
  }
}
</style>