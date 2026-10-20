import type { ReactNode } from "react";
import { ApiError } from "../lib/api";

export function PageHead({ title, intro, actions }: { title: string; intro?: ReactNode; actions?: ReactNode }) {
  return (
    <header className="page-head">
      <div>
        <h1>{title}</h1>
        {intro && <p>{intro}</p>}
      </div>
      {actions && <div className="row">{actions}</div>}
    </header>
  );
}

export function ErrorNote({ error }: { error: unknown }) {
  if (!error) return null;
  const msg = error instanceof ApiError
    ? error.status === 403 ? `You don't have access to this. ${error.message}` : error.message
    : error instanceof Error ? error.message : String(error);
  return <div className="note error" role="alert">{msg}</div>;
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return <div className="empty"><h2>{title}</h2>{children}</div>;
}

export function Metric({ label, value, hint }: { label: string; value: ReactNode; hint?: ReactNode }) {
  return (
    <div className="metric">
      <span className="k">{label}</span>
      <span className="v">{value}</span>
      {hint && <span className="h">{hint}</span>}
    </div>
  );
}

export const pct = (x: number | null | undefined, digits = 1) => (x === null || x === undefined || Number.isNaN(x) ? "—" : `${(x * 100).toFixed(digits)}%`);
export const fix = (x: number | null | undefined, digits = 3) => (x === null || x === undefined || Number.isNaN(x) ? "—" : x.toFixed(digits));
export const when = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleString("en-GB", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : "—";
export const statusClass = (s: string) => s.replace(/\s+/g, "-");
