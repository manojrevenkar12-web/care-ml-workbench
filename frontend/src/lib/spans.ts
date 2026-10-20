import type { Label, Span, Surrogate } from "./types";

/**
 * Keep entity spans valid while the transcript is edited. An edit is the region between the
 * common prefix and the common suffix of old and new text: spans before it stay, spans after it
 * shift by the length difference, spans touching it are dropped (the annotator re-labels them).
 */
export function rebase<T extends { start: number; end: number }>(oldText: string, newText: string, spans: T[]): T[] {
  if (oldText === newText) return spans;
  let p = 0;
  const max = Math.min(oldText.length, newText.length);
  while (p < max && oldText[p] === newText[p]) p++;
  let s = 0;
  while (s < max - p && oldText[oldText.length - 1 - s] === newText[newText.length - 1 - s]) s++;
  const oldEnd = oldText.length - s;
  const delta = newText.length - oldText.length;
  return spans.flatMap((sp) => {
    if (sp.end <= p) return [sp];
    if (sp.start >= oldEnd) return [{ ...sp, start: sp.start + delta, end: sp.end + delta }];
    return [];
  });
}

const isWord = (c: string | undefined) => !!c && /[\p{L}\p{N}/.,%[\]+-]/u.test(c) && c !== " ";

/** Snap a raw selection to whole words and trim surrounding spaces and punctuation. */
export function snap(text: string, start: number, end: number): [number, number] | null {
  let a = Math.max(0, Math.min(start, end));
  let b = Math.min(text.length, Math.max(start, end));
  while (a > 0 && isWord(text[a - 1]) && isWord(text[a])) a--;
  while (b < text.length && isWord(text[b]) && isWord(text[b - 1])) b++;
  while (a < b && /[\s.,:;!?]/.test(text[a])) a++;
  while (b > a && /[\s.,:;!?]/.test(text[b - 1])) b--;
  return b > a ? [a, b] : null;
}

/** Add a span unless it overlaps an existing one (labels never overlap in this schema). */
export function addSpan(spans: Span[], start: number, end: number, label: Label): { spans: Span[]; error?: string } {
  if (spans.some((s) => s.start < end && start < s.end)) return { spans, error: "overlaps an existing label — remove that one first" };
  return { spans: [...spans, { start, end, label }].sort((x, y) => x.start - y.start) };
}

export interface Segment { start: number; end: number; text: string; span?: Span; surrogate?: Surrogate; suggested?: boolean }

/** Split text into segments at every span and surrogate boundary, for rendering. */
export function segments(text: string, spans: Span[], surrogates: Surrogate[] = [], suggestions: Span[] = []): Segment[] {
  const cuts = new Set<number>([0, text.length]);
  for (const s of [...spans, ...surrogates, ...suggestions]) {
    if (s.start >= 0 && s.end <= text.length) { cuts.add(s.start); cuts.add(s.end); }
  }
  const sorted = [...cuts].sort((a, b) => a - b);
  const out: Segment[] = [];
  for (let i = 0; i + 1 < sorted.length; i++) {
    const [a, b] = [sorted[i], sorted[i + 1]];
    if (a === b) continue;
    const span = spans.find((s) => s.start <= a && b <= s.end);
    const sugg = !span ? suggestions.find((s) => s.start <= a && b <= s.end) : undefined;
    out.push({
      start: a, end: b, text: text.slice(a, b),
      span: span ?? sugg,
      suggested: !!sugg,
      surrogate: surrogates.find((s) => s.start <= a && b <= s.end),
    });
  }
  return out;
}

export const LABEL_STYLE: Record<Label, { bg: string; fg: string; name: string }> = {
  VITAL_SIGN: { bg: "#dcebff", fg: "#1e4e8c", name: "Vital sign" },
  MEDICATION: { bg: "#f1e3fb", fg: "#6b2e8f", name: "Medication" },
  DOSAGE: { bg: "#fbe4ef", fg: "#9d174d", name: "Dosage" },
  WOUND: { bg: "#ffe6d8", fg: "#9a3412", name: "Wound" },
  PAIN: { bg: "#fde2e2", fg: "#991b1b", name: "Pain" },
  MOBILITY: { bg: "#ddf3e5", fg: "#166534", name: "Mobility" },
  NUTRITION: { bg: "#ecf7cf", fg: "#3f6212", name: "Nutrition" },
  EXCRETION: { bg: "#ddeffb", fg: "#075985", name: "Excretion" },
  MOOD: { bg: "#ece8fd", fg: "#5b21b6", name: "Mood" },
  SLEEP: { bg: "#e5e9ef", fg: "#334155", name: "Sleep" },
};
