import type {
  Annotation, AuditPage, Comparison, EntityEval, ExploreRow, Job, LabelInfo, Me, Model, Snapshot, Span, Stats, TaskView,
} from "./types";
import { getToken } from "./auth";

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public body: unknown) {
    super(message);
  }
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: { Authorization: `Bearer ${getToken() ?? ""}`, ...(body !== undefined ? { "Content-Type": "application/json" } : {}) },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (res.status === 204) return null as T;
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(res.status, data.error ?? "error", data.message ?? res.statusText, data);
  return data as T;
}

const qs = (o: Record<string, string | number | undefined | null>) =>
  Object.entries(o).filter(([, v]) => v !== undefined && v !== null && v !== "").map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`).join("&");

export const api = {
  me: () => call<Me>("GET", "/api/me"),
  labels: () => call<LabelInfo[]>("GET", "/api/labels"),
  stats: (p: string) => call<Stats>("GET", `/api/projects/${p}/stats`),
  nextTask: (p: string, kind: "ANNOTATE" | "REVIEW") => call<TaskView | null>("POST", `/api/projects/${p}/tasks/next?kind=${kind}`),
  submit: (task: string, transcript: string, entities: Span[], seconds: number) =>
    call<Annotation>("POST", `/api/tasks/${task}/submit`, { transcript, entities, seconds_spent: seconds }),
  adjudicate: (task: string, transcript: string, entities: Span[]) => call<Annotation>("POST", `/api/tasks/${task}/adjudicate`, { transcript, entities }),
  items: (p: string, f: { status?: string; facility?: string; label?: string; q?: string; limit?: number; offset?: number }) =>
    call<{ total: number; rows: ExploreRow[] }>("GET", `/api/projects/${p}/items?${qs(f)}`),
  reveal: (item: string, reason: string) => call<{ item_id: string; original: string; audit_seq: number }>("POST", `/api/items/${item}/reveal`, { reason }),
  snapshots: (p: string) => call<Snapshot[]>("GET", `/api/projects/${p}/snapshots`),
  createSnapshot: (p: string) => call<Snapshot>("POST", `/api/projects/${p}/snapshots`),
  jobs: (p: string) => call<Job[]>("GET", `/api/projects/${p}/jobs`),
  startJob: (p: string, snapshot: string, params: Record<string, string>) => call<Job>("POST", `/api/projects/${p}/jobs`, { snapshot_id: snapshot, model: "entity-tagger", params }),
  cancelJob: (job: string) => call<Job>("POST", `/api/jobs/${job}/cancel`),
  models: (p: string) => call<Model[]>("GET", `/api/projects/${p}/models`),
  evaluate: (snapshot: string, model: string) => call<EntityEval>("GET", `/api/snapshots/${snapshot}/evaluate?${qs({ model })}`),
  compare: (snapshot: string, a: string, b: string) => call<Comparison>("GET", `/api/snapshots/${snapshot}/compare?${qs({ a, b })}`),
  playground: (p: string, model: string, text: string) => call<{ entities: Span[] }>("POST", `/api/projects/${p}/playground`, { model, text }),
  audit: (tenant: string, action?: string) => call<AuditPage>("GET", `/api/tenants/${tenant}/audit?${qs({ action, limit: 200 })}`),
};
