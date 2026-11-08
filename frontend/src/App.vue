<script setup lang="ts">
import { computed, onUnmounted, reactive, ref } from 'vue'
import { readApiError } from './api'
import { usePaymentPolling } from './composables/usePaymentPolling'
import type { Payment, PaymentStatus } from './types'

const {
  payment,
  networkError,
  isPolling,
  start: startPolling,
} = usePaymentPolling()

const CREATE_TIMEOUT_MS = 10_000

const form = reactive({
  amount: '',
  currency: '',
  customerReference: '',
})

const submitting = ref(false)
const formMessage = ref<string | null>(null)
const fieldErrors = ref<Record<string, string>>({})

let createController: AbortController | null = null

const STATUS_LABELS: Record<PaymentStatus, string> = {
  PENDING: 'Pending',
  SUCCEEDED: 'Succeeded',
  FAILED: 'Failed',
  UNKNOWN: 'Unknown',
}

const STATUS_DESCRIPTIONS: Record<PaymentStatus, string> = {
  PENDING: 'The provider has not reported an outcome yet.',
  SUCCEEDED: 'The provider confirmed this payment.',
  FAILED: 'The provider declined this payment.',
  UNKNOWN: 'The provider returned a status this app does not recognise.',
}

const currentStatus = computed<PaymentStatus | 'SUBMITTING' | null>(() => {
  if (submitting.value) return 'SUBMITTING'
  return payment.value ? payment.value.status : null
})

const statusLabel = computed(() => {
  if (currentStatus.value === null) return 'No payment'
  if (currentStatus.value === 'SUBMITTING') return 'Submitting'
  return STATUS_LABELS[currentStatus.value]
})

const statusDescription = computed(() => {
  if (currentStatus.value === null) return ''
  if (currentStatus.value === 'SUBMITTING') return 'Sending the payment to the backend…'
  return STATUS_DESCRIPTIONS[currentStatus.value]
})

const stateClass = computed(() => {
  if (currentStatus.value === null) return 'state-empty'
  return `state-${currentStatus.value.toLowerCase()}`
})

function hasFieldErrors(errors: Record<string, string> | undefined): boolean {
  return errors !== undefined && Object.keys(errors).length > 0
}

function formatAmount(amount: number, currency: string): string {
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amount)
  } catch {
    return `${amount.toFixed(2)} ${currency}`
  }
}

function formatTimestamp(value: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return date.toLocaleString()
}

async function submit(): Promise<void> {
  if (submitting.value) return

  formMessage.value = null
  fieldErrors.value = {}
  submitting.value = true

  createController?.abort()
  const controller = new AbortController()
  createController = controller
  let timedOut = false
  const timeout = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, CREATE_TIMEOUT_MS)

  try {
    const response = await fetch('/payments', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({
        amount: form.amount.trim(),
        currency: form.currency.trim(),
        customerReference: form.customerReference.trim(),
      }),
      signal: controller.signal,
    })

    if (controller.signal.aborted && !timedOut) return

    if (response.status === 201) {
      const created = (await response.json()) as Payment
      if (controller.signal.aborted && !timedOut) return
      submitting.value = false
      startPolling(created)
      return
    }

    const apiError = await readApiError(response)
    if (controller.signal.aborted && !timedOut) return

    if (apiError && hasFieldErrors(apiError.fieldErrors)) {
      fieldErrors.value = apiError.fieldErrors ?? {}
    } else if (apiError) {
      formMessage.value = apiError.message
    } else {
      formMessage.value = `Payment could not be created (HTTP ${response.status}).`
    }
  } catch {
    if (timedOut) {
      formMessage.value =
        'The payment service did not respond in time. Check that the backend is healthy and try again.'
      return
    }
    if (controller.signal.aborted) return
    formMessage.value =
      'Cannot reach the payment service. Check that the backend is running on http://localhost:8080.'
  } finally {
    clearTimeout(timeout)
    if (createController === controller) createController = null
    if (!controller.signal.aborted || timedOut) submitting.value = false
  }
}

onUnmounted(() => {
  createController?.abort()
  createController = null
})
</script>

<template>
  <main class="app">
    <header class="app-header">
      <h1>Payment Inbox</h1>
      <p class="subtitle">Start a card payment and watch the provider report back.</p>
    </header>

    <div v-if="networkError" class="connection-banner" role="alert">
      <span class="connection-icon" aria-hidden="true">!</span>
      <p>{{ networkError }}</p>
    </div>

    <div class="layout">
      <section class="card" aria-labelledby="form-heading">
        <h2 id="form-heading">New payment</h2>

        <form class="payment-form" novalidate :aria-busy="submitting" @submit.prevent="submit">
          <div class="field">
            <label for="amount">Amount</label>
            <input
              id="amount"
              v-model="form.amount"
              type="text"
              inputmode="decimal"
              placeholder="12"
              autocomplete="off"
              :disabled="submitting"
              :aria-invalid="fieldErrors.amount ? 'true' : undefined"
              :aria-describedby="fieldErrors.amount ? 'amount-error' : undefined"
            />
            <p v-if="fieldErrors.amount" id="amount-error" class="field-error">
              {{ fieldErrors.amount }}
            </p>
          </div>

          <div class="field">
            <label for="currency">Currency (3 letters)</label>
            <input
              id="currency"
              v-model="form.currency"
              type="text"
              maxlength="3"
              placeholder="USD"
              autocomplete="off"
              autocapitalize="characters"
              spellcheck="false"
              :disabled="submitting"
              :aria-invalid="fieldErrors.currency ? 'true' : undefined"
              :aria-describedby="fieldErrors.currency ? 'currency-error' : undefined"
            />
            <p v-if="fieldErrors.currency" id="currency-error" class="field-error">
              {{ fieldErrors.currency }}
            </p>
          </div>

          <div class="field">
            <label for="customer-reference">Customer reference</label>
            <input
              id="customer-reference"
              v-model="form.customerReference"
              type="text"
              maxlength="255"
              placeholder="INV-1042"
              autocomplete="off"
              :disabled="submitting"
              :aria-invalid="fieldErrors.customerReference ? 'true' : undefined"
              :aria-describedby="
                fieldErrors.customerReference ? 'customer-reference-error' : undefined
              "
            />
            <p
              v-if="fieldErrors.customerReference"
              id="customer-reference-error"
              class="field-error"
            >
              {{ fieldErrors.customerReference }}
            </p>
          </div>

          <p v-if="formMessage" class="form-error" role="alert">{{ formMessage }}</p>

          <button type="submit" class="primary-button" :disabled="submitting">
            {{ submitting ? 'Starting payment…' : 'Start payment' }}
          </button>
        </form>
      </section>

      <section
        class="card status-card"
        aria-labelledby="status-heading"
        aria-live="polite"
        aria-atomic="true"
      >
        <div class="panel-header">
          <h2 id="status-heading">Payment status</h2>
          <span v-if="isPolling" class="polling-indicator">Checking every 2s</span>
        </div>

        <div v-if="currentStatus === null" class="state state-empty">
          <span class="badge">No payment</span>
          <p class="state-description">Start a payment to watch its status update here.</p>
        </div>

        <div v-else class="state" :class="stateClass">
          <span class="badge">{{ statusLabel }}</span>
          <p class="state-description">{{ statusDescription }}</p>

          <dl v-if="payment" class="details">
            <div>
              <dt>Amount</dt>
              <dd>{{ formatAmount(payment.amount, payment.currency) }}</dd>
            </div>
            <div>
              <dt>Customer reference</dt>
              <dd>{{ payment.customerReference }}</dd>
            </div>
            <div>
              <dt>Payment ID</dt>
              <dd class="mono">{{ payment.id }}</dd>
            </div>
            <div>
              <dt>Provider payment ID</dt>
              <dd class="mono">{{ payment.providerPaymentId ?? '—' }}</dd>
            </div>
            <div>
              <dt>Provider status</dt>
              <dd class="mono">{{ payment.providerStatus ?? '—' }}</dd>
            </div>
            <div>
              <dt>Last callback</dt>
              <dd>{{ formatTimestamp(payment.lastCallbackOccurredAt) }}</dd>
            </div>
          </dl>

          <p v-if="isPolling" class="polling-note">
            Refreshing while the payment is open; polling stops on a final status.
          </p>
        </div>
      </section>
    </div>
  </main>
</template>
