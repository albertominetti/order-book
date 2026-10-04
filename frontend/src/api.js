const API_BASE = '/api'
const DEFAULT_TRADE_LIMIT = 20

export class ApiError extends Error {
  constructor(message, details = {}) {
    super(message)
    this.name = 'ApiError'
    this.code = details.code || 'UNKNOWN_ERROR'
    this.status = details.status || 0
    this.path = details.path || ''
    this.violations = Array.isArray(details.violations) ? details.violations : []
  }
}

function describeUnknown(error, path) {
  if (error instanceof ApiError) {
    return error
  }
  if (error instanceof TypeError) {
    return new ApiError('Cannot reach the API. Is the server running?', {
      code: 'NETWORK_ERROR',
      path
    })
  }
  const message = error && error.message ? error.message : 'Unexpected client error'
  return new ApiError(message, { code: 'CLIENT_ERROR', path })
}

async function readJson(response) {
  try {
    const text = await response.text()
    if (!text) {
      return null
    }
    return JSON.parse(text)
  } catch {
    return null
  }
}

async function request(path, options = {}) {
  const { method = 'GET', body } = options
  const hasBody = body !== undefined

  let response
  try {
    response = await fetch(API_BASE + path, {
      method,
      headers: hasBody
        ? { Accept: 'application/json', 'Content-Type': 'application/json' }
        : { Accept: 'application/json' },
      body: hasBody ? JSON.stringify(body) : undefined
    })
  } catch (error) {
    throw describeUnknown(error, API_BASE + path)
  }

  const payload = await readJson(response)

  if (!response.ok) {
    throw new ApiError(
      (payload && payload.message) || 'Request failed with HTTP ' + response.status,
      {
        code: (payload && payload.code) || 'HTTP_' + response.status,
        status: response.status,
        path: (payload && payload.path) || API_BASE + path,
        violations: (payload && payload.violations) || []
      }
    )
  }

  return payload
}

export function listInstruments() {
  return request('/instruments')
}

export function getOrderBook(symbol) {
  return request('/instruments/' + encodeURIComponent(symbol) + '/orderbook')
}

export function getRecentTrades(symbol, limit = DEFAULT_TRADE_LIMIT) {
  const safeLimit = Number.isFinite(limit) && limit > 0 ? Math.floor(limit) : DEFAULT_TRADE_LIMIT
  return request('/instruments/' + encodeURIComponent(symbol) + '/trades?limit=' + safeLimit)
}

export function submitOrder(order) {
  return request('/orders', { method: 'POST', body: order })
}

export function getOrder(id) {
  return request('/orders/' + encodeURIComponent(id))
}

export function cancelOrder(id) {
  return request('/orders/' + encodeURIComponent(id), { method: 'DELETE' })
}

export function isUnknownInstrument(error) {
  return error instanceof ApiError && error.code === 'UNKNOWN_INSTRUMENT'
}

export function describeError(error) {
  if (!error) {
    return ''
  }
  const message = error.message || 'Unexpected error'
  return error.code && error.code !== 'UNKNOWN_ERROR' ? error.code + ': ' + message : message
}

export const tradeLimit = DEFAULT_TRADE_LIMIT