import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Transcript } from "../components/Transcript";
import { Empty, ErrorNote, PageHead } from "../components/ui";
import { api, ApiError } from "../lib/api";
import type { Project } from "../lib/types";

const EXAMPLE = "Vitalzeichen: RR 135/85, Puls 78. Klagt über Schmerzen im Knie, Stärke 4 von 10. Ibuprofen 400 mg gegeben. Hat das Frühstück vollständig gegessen. Nachts unruhig geschlafen.";

export function Playground({ project }: { project: Project }) {
  const models = useQuery({ queryKey: ["models", project.id], queryFn: () => api.models(project.id) });
  const [model, setModel] = useState("");
  const [text, setText] = useState(EXAMPLE);
  const m = model || models.data?.[models.data.length - 1]?.name || "";
  const run = useMutation({ mutationFn: () => api.playground(project.id, m, text) });
  const phi = run.error instanceof ApiError && run.error.code === "phi_in_input";

  return (
    <>
      <PageHead title="Playground" intro="Try a trained model on your own sentence. Don't paste real records: text that looks like it identifies a person is refused, and nothing you type is stored." />
      {models.data?.length === 0 ? <div className="panel"><Empty title="No trained models yet">Train a model to try it here.</Empty></div> : (
        <div className="stack" style={{ maxWidth: 860 }}>
          <form className="panel panel-pad stack" onSubmit={(e) => { e.preventDefault(); run.mutate(); }}>
            <label className="field"><span>Model</span>
              <select value={m} onChange={(e) => setModel(e.target.value)} style={{ maxWidth: 320 }}>{models.data?.map((x) => <option key={x.name}>{x.name}</option>)}</select>
            </label>
            <label className="field"><span>Care note</span>
              <textarea rows={4} value={text} onChange={(e) => { setText(e.target.value); run.reset(); }} style={{ fontFamily: "var(--serif)", fontSize: 17, lineHeight: 1.6 }} />
            </label>
            <div><button className="btn primary" disabled={!m || !text.trim() || run.isPending}>Find care information</button></div>
          </form>
          {phi && <div className="note phi">This text looks like it contains a real identifier, so it wasn't sent to the model. Remove names, dates of birth, phone numbers or insurance numbers and try again.</div>}
          {!phi && <ErrorNote error={run.error} />}
          {run.data && (
            <section className="panel panel-pad stack" aria-label="Result">
              <p className="small muted">{run.data.entities.length} {run.data.entities.length === 1 ? "entity" : "entities"} found by {m}</p>
              <Transcript text={text} spans={run.data.entities} />
            </section>
          )}
        </div>
      )}
    </>
  );
}
