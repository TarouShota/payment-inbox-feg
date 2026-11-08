export type PaymentStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'

export interface Payment {
  id: string
  status: PaymentStatus
  providerStatus: string | null
  providerPaymentId: string | null
  lastCallbackOccurredAt: string | null
  amount: number
  currency: string
  customerReference: string
}

export interface ApiError {
  error: string
  message: string
  fieldErrors?: Record<string, string>
}
