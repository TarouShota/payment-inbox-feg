import { onUnmounted, ref, type Ref } from 'vue'
import { readApiError } from '../api'
import type { Payment, PaymentStatus } from '../types'

const POLL_INTERVAL_MS = 2000

export interface UsePaymentPollingReturn {
  /** The payment currently being watched (updated by each successful poll). */
  payment: Ref<Payment | null>
  /** Connection/refresh problem, separate from the payment status. */
  networkError: Ref<string | null>
  /** True while polling is active (in-flight request or scheduled retry). */
  isPolling: Ref<boolean>
  /** Show `next` immediately and start polling its status. Aborts any previous run. */
  start: (next: Payment) => void
  /** Stop polling and abort the in-flight request, keeping the payment. */
  stop: () => void
  /** Stop polling and clear the payment and error state. */
  reset: () => void
}

function isTerminal(status: PaymentStatus | null | undefined): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED'
}

export function usePaymentPolling(): UsePaymentPollingReturn {
  const payment = ref<Payment | null>(null)
  const networkError = ref<string | null>(null)
  const isPolling = ref(false)

  // Every start/stop/reset bumps the generation. A response whose generation
  // no longer matches is discarded, so stale data can never win.
  let generation = 0
  let timer: ReturnType<typeof setTimeout> | null = null
  let controller: AbortController | null = null

  function clearTimer(): void {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  function abortInFlight(): void {
    if (controller !== null) {
      controller.abort()
      controller = null
    }
  }

  function scheduleNext(id: string, gen: number): void {
    clearTimer()
    timer = setTimeout(() => {
      timer = null
      if (gen !== generation) return
      void fetchLatest(id, gen)
    }, POLL_INTERVAL_MS)
  }

  async function fetchLatest(id: string, gen: number): Promise<void> {
    const current = new AbortController()
    controller = current

    try {
      const response = await fetch(`/payments/${encodeURIComponent(id)}`, {
        headers: { Accept: 'application/json' },
        signal: current.signal,
      })

      if (gen !== generation) return

      if (!response.ok) {
        const apiError = await readApiError(response)
        if (gen !== generation) return
        networkError.value =
          apiError?.message ?? `Could not refresh this payment (HTTP ${response.status}).`

        // 400/404 are permanent; other statuses may be transient, so retry
        // while the payment is still open.
        const permanent = response.status === 400 || response.status === 404
        if (permanent || isTerminal(payment.value?.status)) {
          isPolling.value = false
        } else {
          scheduleNext(id, gen)
        }
        return
      }

      const latest = (await response.json()) as Payment
      if (gen !== generation) return

      payment.value = latest
      networkError.value = null

      if (isTerminal(latest.status)) {
        isPolling.value = false
        return
      }

      scheduleNext(id, gen)
    } catch {
      if (gen !== generation) return
      if (current.signal.aborted) return

      // The provider may still be working: keep the banner up and keep retrying.
      networkError.value =
        'Cannot reach the payment service. Retrying while the payment is still open.'
      if (!isTerminal(payment.value?.status)) {
        scheduleNext(id, gen)
      } else {
        isPolling.value = false
      }
    } finally {
      if (controller === current) controller = null
    }
  }

  function start(next: Payment): void {
    stop()
    payment.value = next
    networkError.value = null

    if (isTerminal(next.status)) return

    isPolling.value = true
    void fetchLatest(next.id, generation)
  }

  function stop(): void {
    generation += 1
    clearTimer()
    abortInFlight()
    isPolling.value = false
  }

  function reset(): void {
    stop()
    payment.value = null
    networkError.value = null
  }

  onUnmounted(stop)

  return { payment, networkError, isPolling, start, stop, reset }
}
