import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { selectionOffsets, Transcript } from "../components/Transcript";
import { Empty, ErrorNote, PageHead, when } from "../components/ui";
import { api, ApiError } from "../lib/api";
import { useLabels } from "../lib/session";
import { addSpan, LABEL_STYLE, rebase, snap } from "../lib/spans";
import type { Annotation, Label, Project, Span, Surrogate, TaskView } from "../lib/types";

type Mode = "label" | "edit";

export function Annotate({ project, kind }: { project: Project; kind: "ANNOTATE" | "REVIEW" }) {
  const qc = useQueryClient();
  const next = useQuery({
    queryKey: ["next", project.id, kind],
    queryFn: () => api.nextTask(project.id, kind),
    staleTime: Infinity,
    gcTime: 0,
  });
  const review = kind === "REVIEW";
  const title = review ? "Review" : "Annotate";
  const intro = review
    ? "Two annotators disagreed on these transcripts. Compare their versions and save the one that should go into training."
    : "Correct what the speech model misheard, then label the care information. Highlighted amber words are replaced identifiers — leave them as they are.";

  return (
    <>
      <PageHead title={title} intro={intro} />
      {next.isPending && <p className="muted">Finding the next transcript…</p>}
      {next.error && <ErrorNote error={next.error} />}
      {next.data === null && (
        <div className="panel">
          <Empty title={review ? "Nothing to review" : "You're all caught up"}>
            <p>{review ? "Disagreements appear here as annotators submit their work." : "Every open transcript has been annotated by you or is being worked on by someone else."}</p>
            <button className="btn" onClick={() => next.refetch()}>Check again</button>
          </Empty>
        </div>
      )}
      {next.data && (
        <Editor
          key={next.data.task.id}
          view={next.data}
          review={review}
          onDone={() => {
            qc.invalidateQueries({ queryKey: ["stats", project.id] });
            next.refetch();
          }}
        />
      )}
    </>
  );
}

function Editor({ view, review, onDone }: { view: TaskView; review: boolean; onDone: () => void }) {
  const labels = useLabels();
  const { item } = view;
  const start = useRef(Date.now());
  const docRef = useRef<HTMLDivElement>(null);
  const [mode, setMode] = useState<Mode>(review ? "label" : "edit");
  const [text, setText] = useState(review && view.existing[0] ? view.existing[0].transcript : item.asr_text);
  const [spans, setSpans] = useState<Span[]>(review && view.existing[0] ? view.existing[0].entities : []);
  const [surrogates, setSurrogates] = useState(review ? [] : item.surrogates);
  const [hint, setHint] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  const suggestions = useMemo(
    () => (review ? [] : view.suggestions.filter((s) => !spans.some((x) => x.start < s.end && s.start < x.end))),
    [view.suggestions, spans, review],
  );

  const submit = useMutation({
    mutationFn: () => review
      ? api.adjudicate(view.task.id, text, spans)
      : api.submit(view.task.id, text, spans, Math.round((Date.now() - start.current) / 1000)),
    onSuccess: () => {
      setToast(review ? "Final version saved" : "Submitted");
      setTimeout(onDone, 500);
    },
  });

  const edit = (next: string) => {
    setSpans((s) => rebase(text, next, s));
    setSurrogates((s) => rebase(text, next, s));
    setText(next);
  };

  const label = useCallback((l: Label) => {
    const sel = selectionOffsets(docRef.current);
    if (!sel) { setHint("Select words in the transcript first, then choose a label."); return; }
    const snapped = snap(text, sel[0], sel[1]);
    if (!snapped) return;
    const r = addSpan(spans, snapped[0], snapped[1], l);
    setHint(r.error ?? null);
    setSpans(r.spans);
    window.getSelection()?.removeAllRanges();
  }, [text, spans]);

  const acceptAll = () => setSpans((s) => [...s, ...suggestions].sort((a, b) => a.start - b.start));

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === "Enter") { e.preventDefault(); if (!submit.isPending) submit.mutate(); return; }
      if (e.key === "Escape" && mode === "edit") { setMode("label"); return; }
      if (mode !== "label" || e.ctrlKey || e.metaKey || e.altKey) return;
      const tag = (e.target as HTMLElement).tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;
      const l = labels.data?.find((x) => x.hotkey === e.key);
      if (l) { e.preventDefault(); label(l.name); }
      else if (e.key === "e") { e.preventDefault(); setMode("edit"); }
      else if (e.key === "a" && suggestions.length) { e.preventDefault(); acceptAll(); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const err = submit.error;
  const phiRefused = err instanceof ApiError && err.code === "invalid_annotation" && /PHI/.test(err.message);

  return (
    <div className="annotate">
      <div className="stack">
        {review && view.existing.length >= 2 && <Disagreement a={view.existing[0]} b={view.existing[1]} onPick={(x) => { setText(x.transcript); setSpans(x.entities); }} />}
        <section className="doc" aria-label="Transcript">
          <div className="doc-head">
            <div className="doc-meta">
              <span>Resident <b>{item.subject}</b></span>
              <span>Facility <b>{item.facility_id}</b></span>
              <span>Recorded <b>{when(item.recorded_at)}</b></span>
              <span>Speech model <b>{item.asr_model}</b></span>
            </div>
            <span className="mode-switch" role="group" aria-label="Mode">
              <button type="button" aria-pressed={mode === "edit"} onClick={() => setMode("edit")}>Correct text <kbd>E</kbd></button>
              <button type="button" aria-pressed={mode === "label"} onClick={() => setMode("label")}>Label <kbd>Esc</kbd></button>
            </span>
          </div>
          {mode === "edit" ? (
            <EditableTranscript text={text} spans={spans} surrogates={surrogates} onChange={edit} />
          ) : (
            <Transcript
              ref={docRef}
              text={text}
              spans={spans}
              surrogates={surrogates}
              suggestions={suggestions}
              onRemove={(s) => setSpans((all) => all.filter((x) => x !== s))}
              onAccept={(s) => setSpans((all) => [...all, s].sort((a, b) => a.start - b.start))}
            />
          )}
          {hint && <p className="small muted" role="status" style={{ marginTop: 12 }}>{hint}</p>}
          {phiRefused && (
            <div className="note phi" role="alert" style={{ marginTop: 14 }}>
              Not submitted: {(err as Error).message}. Real names, numbers and dates must never go into training data — keep the surrogate or placeholder from the original transcript.
            </div>
          )}
          {err && !phiRefused && <div style={{ marginTop: 14 }}><ErrorNote error={err} /></div>}
          <div className="submitbar">
            <span className="small muted">
              {spans.length} {spans.length === 1 ? "label" : "labels"}
              {suggestions.length > 0 && <>, {suggestions.length} more suggested by the current model</>}
            </span>
            <div className="row">
              {suggestions.length > 0 && mode === "label" && <button className="btn" onClick={acceptAll}>Accept suggestions <kbd>A</kbd></button>}
              <button className="btn primary" disabled={submit.isPending} onClick={() => submit.mutate()}>
                {review ? "Save final version" : "Submit"} <kbd style={{ background: "transparent", color: "#fff", borderColor: "rgba(255,255,255,.5)" }}>Ctrl ↵</kbd>
              </button>
            </div>
          </div>
        </section>
      </div>

      <aside className="side" aria-label="Labels">
        <div className="panel panel-pad stack" style={{ gap: 10 }}>
          <h3>Labels</h3>
          <div className="labels">
            {labels.data?.map((l) => {
              const st = LABEL_STYLE[l.name];
              return (
                <button key={l.name} className="label-btn" disabled={mode !== "label"} title={l.description} onMouseDown={(e) => e.preventDefault()} onClick={() => label(l.name)}>
                  <span className="swatch" style={{ background: st.bg, color: st.fg }}>{l.hotkey}</span>
                  <span>{st.name}</span>
                </button>
              );
            })}
          </div>
          <p className="small muted">{mode === "label" ? "Select words, then press a number." : <>Press <kbd>Esc</kbd> to start labelling.</>}</p>
        </div>
        <div className="panel panel-pad stack" style={{ gap: 8 }}>
          <div className="legend-phi"><span>Herr Albers</span><span>Replaced identifier — synthetic, keep as is</span></div>
          <div className="legend-phi"><span>[TELEFON]</span><span>Removed identifier — keep the placeholder</span></div>
        </div>
      </aside>
      {toast && <div className="toast" role="status">{toast}</div>}
    </div>
  );
}

/**
 * A plain textarea over a mirrored rendering of the same text: the mirror paints the label and
 * replaced-identifier backgrounds, so annotators still see which words are synthetic while typing.
 */
function EditableTranscript({ text, spans, surrogates, onChange }: { text: string; spans: Span[]; surrogates: Surrogate[]; onChange: (t: string) => void }) {
  const ref = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (el) { el.style.height = "auto"; el.style.height = `${el.scrollHeight + 2}px`; }
  }, [text]);
  return (
    <div className="edit-wrap">
      <div className="edit-mirror" aria-hidden>
        <Transcript text={text + "\n"} spans={spans} surrogates={surrogates} />
      </div>
      <textarea ref={ref} className="transcript-edit" aria-label="Transcript text" value={text} spellCheck={false} autoFocus onChange={(e) => onChange(e.target.value)} />
    </div>
  );
}

function Disagreement({ a, b, onPick }: { a: Annotation; b: Annotation; onPick: (x: Annotation) => void }) {
  return (
    <section className="compare" aria-label="Annotator versions">
      {[a, b].map((x, i) => (
        <div key={x.id} className="panel panel-pad">
          <div className="who">
            <span>Version {i === 0 ? "A" : "B"} by <b style={{ color: "var(--ink)" }}>{x.annotator.replace("user:", "")}</b>, {x.entities.length} labels</span>
            <button className="btn quiet" onClick={() => onPick(x)}>Start from this</button>
          </div>
          <Transcript text={x.transcript} spans={x.entities} compact />
        </div>
      ))}
    </section>
  );
}
