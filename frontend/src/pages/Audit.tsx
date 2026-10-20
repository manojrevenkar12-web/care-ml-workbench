import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { ErrorNote, PageHead, when } from "../components/ui";
import { api } from "../lib/api";
import type { Project } from "../lib/types";

const ACTIONS: Record<string, string> = {
  "": "All events",
  "phi.reveal": "Original record viewed",
  "phi.ingest": "Record ingested",
  "dataset.snapshot": "Dataset version created",
  "dataset.export": "Dataset exported",
  "training.start": "Training started",
  "authz.grant": "Access granted",
  "authz.revoke": "Access removed",
  "ingest.quarantine": "Record held back by PHI check",
};

export function Audit({ project }: { project: Project }) {
  const [action, setAction] = useState("");
  const q = useQuery({ queryKey: ["audit", project.tenant, action], queryFn: () => api.audit(project.tenant, action || undefined) });
  const v = q.data?.verification;
  return (
    <>
      <PageHead title="Audit log" intro={`Every access to original records, every dataset and training run in ${project.tenant}. Events are hash-chained, so any edit or deletion breaks the chain.`} />
      <ErrorNote error={q.error} />
      {v && (
        <div className={`note ${v.intact ? "info" : "error"}`} style={{ marginBottom: 16 }} role="status">
          {v.intact
            ? `Chain verified: all ${v.events.toLocaleString("en")} events are intact.`
            : `Chain broken at event #${v.first_broken_seq}: ${v.reason}. Treat events from there on as untrusted and escalate to security.`}
        </div>
      )}
      <div className="row" style={{ marginBottom: 12 }}>
        <label className="field"><span>Show</span>
          <select value={action} onChange={(e) => setAction(e.target.value)}>{Object.entries(ACTIONS).map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select>
        </label>
      </div>
      <div className="panel" style={{ overflowX: "auto" }}>
        <table className="table">
          <thead><tr><th className="r">#</th><th>When</th><th>Who</th><th>What</th><th>Resource</th><th>Details</th></tr></thead>
          <tbody>
            {q.data?.events.map((e) => (
              <tr key={e.seq}>
                <td className="r">{e.seq}</td>
                <td style={{ whiteSpace: "nowrap" }}>{when(e.at)}</td>
                <td>{e.actor.replace("user:", "")}</td>
                <td className="nowrap">{ACTIONS[e.action] ?? e.action}</td>
                <td className="mono">{e.resource}</td>
                <td className="small">{Object.entries(e.detail).map(([k, val]) => <div key={k}><span className="muted">{k.replace(/_/g, " ")}:</span> {val}</div>)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
