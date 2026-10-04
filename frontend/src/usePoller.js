import { onScopeDispose } from 'vue'

export function usePoller(task, intervalMs = 1000) {
  let timer = null
  let inFlight = false
  let pending = false
  let stopped = false

  async function tick() {
    if (stopped) {
      return
    }
    if (inFlight) {
      pending = true
      return
    }
    inFlight = true
    try {
      await task()
    } catch {
      pending = false
    } finally {
      inFlight = false
      if (pending && !stopped) {
        pending = false
        void tick()
      }
    }
  }

  function stop() {
    stopped = true
    pending = false
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  function start() {
    if (stopped || timer !== null) {
      return
    }
    void tick()
    timer = setInterval(() => {
      void tick()
    }, intervalMs)
  }

  function refresh() {
    void tick()
  }

  start()
  onScopeDispose(stop)

  return { refresh, start, stop }
}