/*
 * The one place this panel talks to the backend.
 *
 * ⚠️ THE ERROR SHAPE. Every failure from this API is `{"error": {"code", "message"}}` --
 * main.py wraps even FastAPI's own 401s and 422s into that envelope so no endpoint can
 * leak a differently shaped error. Reading `body.detail` (FastAPI's default, deliberately
 * overridden) or `body.message` yields undefined, and the panel would render the word
 * "undefined" where the reason belongs. Everything goes through unwrap() below.
 */

const TOKEN_KEY = 'tfides.admin.token';

export class ApiError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

export function storedToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function storeToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}

async function unwrap(response: Response): Promise<never> {
  let code = 'unknown';
  let message = response.statusText;
  try {
    const body = await response.json();
    code = body?.error?.code ?? code;
    message = body?.error?.message ?? message;
  } catch {
    // A response with no JSON body at all -- a proxy error, say. statusText is all there
    // is, and it is better than throwing over the throw.
  }
  throw new ApiError(code, message, response.status);
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = storedToken();
  const response = await fetch(path, {
    ...init,
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...init.headers,
    },
  });

  if (!response.ok) {
    // An expired admin token is the ordinary case here, not an exception: the TTL is an
    // hour and a demo can outlast it. Dropping the token means the router's gate sends
    // the user to the login screen on the next render rather than showing a broken page.
    if (response.status === 401) clearToken();
    return unwrap(response);
  }

  return response.json() as Promise<T>;
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, {
      method: 'POST',
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
};
