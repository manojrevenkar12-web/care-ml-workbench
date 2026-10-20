// Mirrors workbench.json.Codec (Kotlin). Field names are the API contract.
export type Label =
  | "VITAL_SIGN" | "MEDICATION" | "DOSAGE" | "WOUND" | "PAIN"
  | "MOBILITY" | "NUTRITION" | "EXCRETION" | "MOOD" | "SLEEP";

export type PhiKind =
  | "PERSON_NAME" | "DATE_OF_BIRTH" | "INSURANCE_NUMBER" | "PHONE" | "EMAIL"
  | "ADDRESS" | "ROOM" | "AGE_OVER_89" | "DATE";

export interface Span { start: number; end: number; label: Label }
export interface Surrogate { start: number; end: number; kind: PhiKind }

export interface Item {
  id: string; project_id: string; facility_id: string; subject: string; recorded_at: string;
  asr_model: string; asr_text: string; surrogates: Surrogate[]; priority: number;
  reference_text: string | null; reference_entities: Span[] | null; predictions: Record<string, string>;
}

export interface Task {
  id: string; item_id: string; project_id: string; kind: "ANNOTATE" | "REVIEW";
  status: string; assignee: string | null; leased_until: string | null; created_at: string;
}

export interface Annotation {
  id: string; task_id: string; item_id: string; annotator: string; transcript: string;
  entities: Span[]; submitted_at: string; seconds_spent: number; final: boolean;
}

export interface TaskView { task: Task; item: Item; existing: Annotation[]; suggestions: Span[] }

export type Permission = "viewer" | "annotator" | "reviewer" | "admin" | "phi_viewer";
export interface Project { id: string; tenant: string; name: string; description: string; permissions: Record<Permission, boolean> }
export interface Me { user: string; projects: Project[] }
export interface LabelInfo { name: Label; hotkey: string; description: string }

export interface Agreement { items: number; span_f1: number | null; overlap_f1: number | null; token_kappa: number | null; transcript_wer_between: number | null }
export interface Stats {
  items: number; tasks_open: number; tasks_leased: number; annotations: number; done: number; needs_review: number;
  agreement: Agreement; label_counts: Partial<Record<Label, number>>; median_seconds_per_item: number | null;
}

export interface ExploreRow {
  item: Item; annotations: number; status: string; wer: number | null; labels: Label[];
  reference: { transcript: string; entities: Span[]; annotator: string } | null;
}

export interface Snapshot {
  id: string; project_id: string; version: number; created_by: string; created_at: string; manifest_sha256: string;
  counts: Record<"train" | "dev" | "test", number>; label_counts: Record<string, number>; excluded: Record<string, number>;
}

export interface Job {
  id: string; project_id: string; snapshot_id: string; snapshot_sha256: string; model: string; params: Record<string, string>;
  status: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED"; created_by: string; created_at: string;
  started_at: string | null; finished_at: string | null; metrics: Record<string, number>; artifact: string | null; message: string | null;
}

export interface Model { name: string; project_id: string; job_id: string; snapshot_sha256: string; metrics: Record<string, number>; created_at: string }
export interface Prf { precision: number; recall: number; f1: number; support: number }
export interface EntityEval { model: string; examples: number; micro: Prf; per_label: Record<string, Prf>; errors: { text: string; gold: Span[]; predicted: Span[] }[] }
export interface Comparison { model_a: string; model_b: string; items: number; wer_a: number; wer_b: number; difference: number; ci95: [number, number]; by_slice: Record<string, { wer_a: number; wer_b: number }> }
export interface AuditEvent { seq: number; at: string; actor: string; action: string; tenant: string; resource: string; detail: Record<string, string>; hash: string }
export interface AuditPage { verification: { intact: boolean; events: number; first_broken_seq: number | null; reason: string | null }; events: AuditEvent[] }
