import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp } from 'vue'
import { usePaymentPolling, type UsePaymentPollingReturn } from './usePaymentPolling'
import type { Payment, PaymentStatus } from '../types'

function payment(status: PaymentStatus, id = 'payment-1'): Payment {
  return {
    id,
    status,
    providerStatus: null,
    providerPaymentId: `prov-${id}`,
    lastCallbackOccurredAt: null,
    amount: 10,
    currency: 'EUR',
    customerReference: 'REF-1',
  }
}

function response(body: Payment, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response
}

function mountPolling(): { polling: UsePaymentPollingReturn; unmount: () => void } {
  let polling: UsePaymentPollingReturn | undefined
  const app = createApp({
    setup() {
      polling = usePaymentPolling()
      return () => null
    },
  })
  app.mount(document.createElement('div'))
  if (!polling) throw new Error('composable was not created')
  return { polling, unmount: () => app.unmount() }
}

const flushMicrotasks = async (): Promise<void> => {
  await vi.advanceTimersByTimeAsync(0)
}

describe('usePaymentPolling', () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    vi.useFakeTimers()
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('polls every 2s while pending and stops at a terminal status', async () => {
    fetchMock
      .mockResolvedValueOnce(response(payment('PENDING')))
      .mockResolvedValueOnce(response(payment('SUCCEEDED')))

    const { polling, unmount } = mountPolling()
    polling.start(payment('PENDING'))

    await flushMicrotasks()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(polling.payment.value?.status).toBe('PENDING')
    expect(polling.isPolling.value).toBe(true)

    await vi.advanceTimersByTimeAsync(2000)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(polling.payment.value?.status).toBe('SUCCEEDED')
    expect(polling.isPolling.value).toBe(false)

    await vi.advanceTimersByTimeAsync(5000)
    expect(fetchMock).toHaveBeenCalledTimes(2)

    unmount()
  })

  it('does not poll a payment that is already terminal', async () => {
    const { polling, unmount } = mountPolling()
    polling.start(payment('FAILED'))

    await flushMicrotasks()
    expect(fetchMock).not.toHaveBeenCalled()
    expect(polling.isPolling.value).toBe(false)

    unmount()
  })

  it('aborts the old request and ignores its late response when a new payment starts', async () => {
    let resolveFirst!: (value: Response) => void
    const first = new Promise<Response>((resolve) => {
      resolveFirst = resolve
    })
    let firstSignal: AbortSignal | undefined

    fetchMock.mockImplementationOnce((_input: RequestInfo | URL, init?: RequestInit) => {
      firstSignal = init?.signal ?? undefined
      return first
    })
    fetchMock.mockResolvedValueOnce(response(payment('PENDING', 'payment-2')))

    const { polling, unmount } = mountPolling()
    polling.start(payment('PENDING', 'payment-1'))
    await flushMicrotasks()
    expect(firstSignal?.aborted).toBe(false)

    polling.start(payment('PENDING', 'payment-2'))
    expect(firstSignal?.aborted).toBe(true)

    resolveFirst(response(payment('SUCCEEDED', 'payment-1')))
    await flushMicrotasks()

    expect(polling.payment.value?.id).toBe('payment-2')
    expect(polling.payment.value?.status).toBe('PENDING')

    unmount()
  })

  it('keeps retrying after a transient network failure and clears the banner on recovery', async () => {
    fetchMock
      .mockRejectedValueOnce(new TypeError('network down'))
      .mockResolvedValueOnce(response(payment('SUCCEEDED')))

    const { polling, unmount } = mountPolling()
    polling.start(payment('PENDING'))
    await flushMicrotasks()

    expect(polling.networkError.value).toContain('Cannot reach')
    expect(polling.isPolling.value).toBe(true)

    await vi.advanceTimersByTimeAsync(2000)
    expect(polling.payment.value?.status).toBe('SUCCEEDED')
    expect(polling.networkError.value).toBeNull()
    expect(polling.isPolling.value).toBe(false)

    unmount()
  })

  it('stops the scheduled poll when the component unmounts', async () => {
    fetchMock.mockResolvedValue(response(payment('PENDING')))

    const { polling, unmount } = mountPolling()
    polling.start(payment('PENDING'))
    await flushMicrotasks()
    expect(polling.isPolling.value).toBe(true)

    unmount()
    expect(polling.isPolling.value).toBe(false)

    await vi.advanceTimersByTimeAsync(5000)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
