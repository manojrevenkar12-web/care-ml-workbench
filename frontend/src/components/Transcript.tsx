import { forwardRef } from "react";
import { LABEL_STYLE, segments } from "../lib/spans";
import type { Span, Surrogate } from "../lib/types";

const PHI_NAME: Record<string, string> = {
  PERSON_NAME: "name", DATE_OF_BIRTH: "date of birth", INSURANCE_NUMBER: "insurance number", PHONE: "phone",
  EMAIL: "email", ADDRESS: "address", ROOM: "room", AGE_OVER_89: "age", DATE: "date",
};

interface Props {
  text: string;
  spans: Span[];
  surrogates?: Surrogate[];
  suggestions?: Span[];
  compact?: boolean;
  onRemove?: (span: Span) => void;
  onAccept?: (span: Span) => void;
}

/**
 * Renders a de-identified transcript. Entities are tinted by label; replaced PHI (surrogate
 * names, shifted dates, placeholders) is shown with a dotted amber underline so annotators can
 * see which words are synthetic and must not be "corrected" back.
 * Data attributes carry character offsets, so a DOM selection maps back to the text exactly.
 */
export const Transcript = forwardRef<HTMLDivElement, Props>(function Transcript(
  { text, spans, surrogates = [], suggestions = [], compact, onRemove, onAccept }, ref,
) {
  const segs = segments(text, spans, surrogates, suggestions);
  return (
    <div ref={ref} className={`transcript${compact ? " compact" : ""}`} data-testid="transcript">
      {segs.map((s) => {
        const phiTitle = s.surrogate ? `Replaced ${PHI_NAME[s.surrogate.kind] ?? "identifier"} — not the real value` : undefined;
        const inner = s.surrogate
          ? <span className="phi" data-start={s.start} title={phiTitle}>{s.text}</span>
          : <span data-start={s.start}>{s.text}</span>;
        if (!s.span) return <span key={s.start}>{inner}</span>;
        const st = LABEL_STYLE[s.span.label];
        const first = s.start === s.span.start;
        const last = s.end === s.span.end;
        return (
          <span
            key={s.start}
            className={`ent${first ? " first" : ""}${last ? " last" : ""}${s.suggested ? " suggested" : ""}`}
            style={{ background: st.bg, color: st.fg }}
            title={s.suggested ? `Suggested: ${st.name}` : st.name}
          >
            {inner}
            {last && (
              <span className="ent-label" aria-hidden>{st.name}</span>
            )}
            {last && !s.suggested && onRemove && (
              <button type="button" className="ent-x" aria-label={`Remove ${st.name} label`} onClick={() => onRemove(s.span!)}>×</button>
            )}
            {last && s.suggested && onAccept && (
              <button type="button" className="ent-x" aria-label={`Accept suggested ${st.name}`} onClick={() => onAccept(s.span!)}>✓</button>
            )}
          </span>
        );
      })}
    </div>
  );
});

/** Map the current DOM selection inside a Transcript to text offsets. */
export function selectionOffsets(root: HTMLElement | null): [number, number] | null {
  const sel = window.getSelection();
  if (!root || !sel || sel.rangeCount === 0 || sel.isCollapsed) return null;
  const r = sel.getRangeAt(0);
  const pos = (node: Node, offset: number): number | null => {
    if (!root.contains(node)) return null;
    let el: Node | null = node.nodeType === Node.TEXT_NODE ? node.parentNode : node;
    while (el && el !== root && !(el instanceof HTMLElement && el.dataset.start !== undefined)) el = el.parentNode;
    if (!(el instanceof HTMLElement) || el.dataset.start === undefined) return null;
    return Number(el.dataset.start) + (node.nodeType === Node.TEXT_NODE ? offset : 0);
  };
  const a = pos(r.startContainer, r.startOffset);
  const b = pos(r.endContainer, r.endOffset);
  return a === null || b === null ? null : [a, b];
}
