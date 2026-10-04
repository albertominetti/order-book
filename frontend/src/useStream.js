import { onScopeDispose, ref, watch } from 'vue'

const BOOK_EVENT = 'book'
const TRADES_EVENT = 'trades'
const INSTRUMENTS_EVENT = 'instruments'

const INSTRUMENTS_STREAM = '/api/instruments/stream'

/**
 * Live market data over Server-Sent Events, the push channel of the order book API.
 *
 * Two streams are opened with the native `EventSource`, so no dependency is added:
 *
 * - `/api/instruments/{symbol}/stream`, reopened whenever the symbol changes, which pushes a
 *   `book` event with the aggregated snapshot and a `trades` event with the recent trade tape;
 * - `/api/instruments/stream`, opened once, which pushes the `instruments` event with the list of
 *   the active instruments.
 *
 * The payloads are the very same JSON the polling endpoints return, so a handler receives exactly
 * what a REST call would have returned and can be shared by both channels.
 *
 * The reactive `connected` flag is the contract with the caller: while it is true the stream is
 * the primary source of live updates, and when it turns false the caller falls back to polling. Any
 * stream error, a proxy that drops the connection or a browser without `EventSource` at all closes
 * both streams and flips the flag, so a page never waits for data that is not coming.
 *
 * Every valid symbol can be streamed, even one with no book yet: that subscriber simply receives an
 * empty book first, then the real state from the moment the first order creates the instrument.
 *
 * @param {import('vue').Ref<string>} symbol active instrument, watched for changes
 * @param {{
 *   onBook?: (book: object) => void,
 *   onTrades?: (trades: Array<object>) => void,
 *   onInstruments?: (instruments: Array<object>) => void
 * }} [handlers] callbacks for the three events
 * @returns {{ connected: import('vue').Ref<boolean>, connect: () => void, disconnect: () => void }}
 */
export function useStream(symbol, handlers = {}) {
  const connected = ref(false)
  const supported = typeof EventSource !== 'undefined'

  let bookSource = null
  let instrumentsSource = null
  let bookOpen = false
  let instrumentsOpen = false
  let disposed = false

  function instrumentStreamUrl(activeSymbol) {
    return '/api/instruments/' + encodeURIComponent(activeSymbol) + '/stream'
  }

  function notify(handler, payload) {
    if (typeof handler !== 'function') {
      return
    }
    try {
      handler(payload)
    } catch {
      // A failing handler must never break the stream: the poller keeps the panels up to date.
    }
  }

  /** Parses one SSE frame and hands the payload over. */
  function listen(source, event, handler) {
    source.addEventListener(event, (message) => {
      const raw = message && typeof message.data === 'string' ? message.data : ''
      if (!raw) {
        return
      }
      try {
        notify(handler, JSON.parse(raw))
      } catch {
        // A frame that is not JSON is dropped, the next one will be fine.
      }
    })
  }

  /** Closes one source and drops its listeners, a closed source never fires again. */
  function closeSource(source) {
    if (!source) {
      return
    }
    source.onopen = null
    source.onerror = null
    try {
      source.close()
    } catch {
      // Already closed by the browser, nothing left to do.
    }
  }

  function disconnect() {
    closeSource(bookSource)
    closeSource(instrumentsSource)
    bookSource = null
    instrumentsSource = null
    bookOpen = false
    instrumentsOpen = false
    connected.value = false
  }

  /**
   * The first error on either stream is fatal for the whole push channel, so both are closed and
   * the caller is told to poll again.
   */
  function onStreamFailure() {
    if (disposed) {
      return
    }
    disconnect()
  }

  /** True once both streams are established, which is what makes the push channel usable. */
  function markConnected() {
    connected.value = bookOpen && instrumentsOpen
  }

  function connect() {
    disconnect()
    if (disposed || !supported) {
      return
    }
    const activeSymbol = String(symbol.value || '').trim()
    if (!activeSymbol) {
      return
    }

    try {
      bookSource = new EventSource(instrumentStreamUrl(activeSymbol))
      instrumentsSource = new EventSource(INSTRUMENTS_STREAM)

      listen(bookSource, BOOK_EVENT, handlers.onBook)
      listen(bookSource, TRADES_EVENT, handlers.onTrades)
      listen(instrumentsSource, INSTRUMENTS_EVENT, handlers.onInstruments)

      bookSource.onopen = () => {
        bookOpen = true
        markConnected()
      }
      bookSource.onerror = onStreamFailure
      instrumentsSource.onopen = () => {
        instrumentsOpen = true
        markConnected()
      }
      instrumentsSource.onerror = onStreamFailure
    } catch {
      // A browser that refuses to build an EventSource is simply a browser without streams.
      disconnect()
    }
  }

  // Switching instrument means switching stream: the old one is closed before the new one opens.
  watch(symbol, connect)
  onScopeDispose(() => {
    disposed = true
    disconnect()
  })

  connect()

  return { connected, connect, disconnect }
}