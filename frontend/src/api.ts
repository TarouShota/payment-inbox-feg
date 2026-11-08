import type { ApiError } from './types'

/**
 * Reads a backend error body defensively. Returns null when the response is
 * not the documented `{ error, message, fieldErrors? }` shape.
 */
export async function readApiError(response: Response): Promise<ApiError | null> {
  try {
    const body: unknown = await response.json()
    if (typeof body !== 'object' || body === null) {
      return null
    }

    const candidate = body as Partial<ApiError>
    if (typeof candidate.error !== 'string' || typeof candidate.message !== 'string') {
      return null
    }

    return {
      error: candidate.error,
      message: candidate.message,
      fieldErrors: candidate.fieldErrors,
    }
  } catch {
    return null
  }
}
