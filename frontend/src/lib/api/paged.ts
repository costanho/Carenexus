import { api } from '@/lib/auth/auth.interceptor';
import type { PageResponse } from './types';

// Helpers for the pagination envelope (CONTRACTS 3.2): page is zero-based, size is clamped by the server to 1..100.

export const DEFAULT_PAGE_SIZE = 20;
export const MAX_PAGE_SIZE = 100;

export function emptyPage<T>(page = 0, size = DEFAULT_PAGE_SIZE): PageResponse<T> {
  return { content: [], page, size, totalElements: 0, totalPages: 0 };
}

/** Query parameters for a paged endpoint, with the same clamp the server applies. */
export function pageParams(page: number, size: number = DEFAULT_PAGE_SIZE): { page: number; size: number } {
  const safePage = Number.isFinite(page) && page > 0 ? Math.floor(page) : 0;
  const safeSize = Number.isFinite(size) ? Math.min(MAX_PAGE_SIZE, Math.max(1, Math.floor(size))) : DEFAULT_PAGE_SIZE;
  return { page: safePage, size: safeSize };
}

function count(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : fallback;
}

/**
 * Reads a response body as a page. Accepts the envelope and, for legacy endpoints that still answer with a
 * plain array, treats the array as the only page. Anything else is an empty page.
 */
export function toPage<T>(data: unknown, page = 0, size = DEFAULT_PAGE_SIZE): PageResponse<T> {
  if (Array.isArray(data)) {
    return { content: data as T[], page: 0, size: data.length, totalElements: data.length, totalPages: data.length > 0 ? 1 : 0 };
  }
  if (data && typeof data === 'object' && Array.isArray((data as { content?: unknown }).content)) {
    const body = data as Partial<PageResponse<T>>;
    const content = body.content as T[];
    const total = count(body.totalElements, content.length);
    const pageSize = count(body.size, size) || size;
    return {
      content,
      page:          count(body.page, page),
      size:          pageSize,
      totalElements: total,
      totalPages:    count(body.totalPages, pageSize > 0 ? Math.ceil(total / pageSize) : 0),
    };
  }
  return emptyPage<T>(page, size);
}

/** Maps the rows of a page, keeping the paging fields. */
export function mapPage<T, U>(source: PageResponse<T>, map: (row: T) => U): PageResponse<U> {
  return { ...source, content: source.content.map(map) };
}

/** True when the server has rows after this page. */
export function hasNextPage(page: PageResponse<unknown>): boolean {
  return page.page + 1 < page.totalPages;
}

type QueryValue = string | number | boolean | null | undefined;

/** Drops empty filters so they are not sent as `?status=`. */
function cleanParams(params: Record<string, QueryValue> | undefined): Record<string, string | number | boolean> {
  const out: Record<string, string | number | boolean> = {};
  for (const [key, value] of Object.entries(params ?? {})) {
    if (value === null || value === undefined || value === '') continue;
    out[key] = value;
  }
  return out;
}

/**
 * GET one page of a list endpoint: `fetchPage<InvoiceDto>('/api/billing/invoices', page, size, { status })`.
 * Rejects with the axios error (use `apiError` / `apiErrorMessage` to show it).
 */
export async function fetchPage<T>(
  url: string,
  page: number,
  size: number = DEFAULT_PAGE_SIZE,
  params?: Record<string, QueryValue>,
): Promise<PageResponse<T>> {
  const paging = pageParams(page, size);
  const { data } = await api.get<unknown>(url, { params: { ...cleanParams(params), ...paging } });
  return toPage<T>(data, paging.page, paging.size);
}
