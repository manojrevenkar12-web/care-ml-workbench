import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { mockApi, renderWithClient } from "../test-utils";
import type { Project, TaskView } from "../lib/types";
import { Annotate } from "./Annotate";

const project: Project = {
  id: "p1", tenant: "t1", name: "Nord", description: "",
  permissions: { viewer: true, annotator: true, reviewer: false, admin: false, phi_viewer: false },
};
const text = "Frau Albers hat Schmerzen im Knie. Telefon [TELEFON].";
const view: TaskView = {
  task: { id: "task1", item_id: "i1", project_id: "p1", kind: "ANNOTATE", status: "LEASED", assignee: "user:anna", leased_until: null, created_at: "2026-01-01T00:00:00Z" },
  item: {
    id: "i1", project_id: "p1", facility_id: "kiel", subject: "r_abc", recorded_at: "2026-01-01T08:00:00Z", asr_model: "asr-v1",
    asr_text: text, surrogates: [{ start: 5, end: 11, kind: "PERSON_NAME" }, { start: 43, end: 52, kind: "PHONE" }], priority: 1,
    reference_text: null, reference_entities: null, predictions: {},
  },
  existing: [],
  suggestions: [{ start: 16, end: 33, label: "PAIN" }],
};
const labels = [{ name: "PAIN", hotkey: "5", description: "pain" }];

function setup(submit: (b: unknown) => { status?: number; json?: unknown }) {
  return mockApi({
    "POST /api/projects/p1/tasks/next": () => ({ json: view }),
    "GET /api/labels": () => ({ json: labels }),
    "POST /api/tasks/task1/submit": submit,
  });
}

describe("Annotate", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("marks replaced identifiers and shows the model's suggestions", async () => {
    setup(() => ({ status: 201 }));
    renderWithClient(<Annotate project={project} kind="ANNOTATE" />);
    await userEvent.click(await screen.findByRole("button", { name: /^Label/ }));
    const phi = screen.getByTitle(/Replaced name/);
    expect(phi).toHaveTextContent("Albers");
    expect(screen.getByTitle("Suggested: Pain")).toHaveTextContent("Schmerzen im Knie");
  });

  it("accepts suggestions with A and submits with Ctrl+Enter", async () => {
    const calls = setup(() => ({ status: 201, json: {} }));
    renderWithClient(<Annotate project={project} kind="ANNOTATE" />);
    await userEvent.click(await screen.findByRole("button", { name: /^Label/ }));
    act(() => { fireEvent.keyDown(window, { key: "a" }); });
    expect(screen.getByText(/1 label$/)).toBeInTheDocument();
    act(() => { fireEvent.keyDown(window, { key: "Enter", ctrlKey: true }); });
    await waitFor(() => expect(calls.some((c) => c.path === "/api/tasks/task1/submit")).toBe(true));
    const body = calls.find((c) => c.path === "/api/tasks/task1/submit")!.body as { transcript: string; entities: unknown[] };
    expect(body.transcript).toBe(text);
    expect(body.entities).toEqual([{ start: 16, end: 33, label: "PAIN" }]);
  });

  it("keeps labels attached to their words while the text is corrected", async () => {
    const calls = setup(() => ({ status: 201, json: {} }));
    renderWithClient(<Annotate project={project} kind="ANNOTATE" />);
    await userEvent.click(await screen.findByRole("button", { name: /^Label/ }));
    await userEvent.click(screen.getByRole("button", { name: "Accept suggested Pain" }));
    await userEvent.click(screen.getByRole("button", { name: /^Correct text/ }));
    const box = screen.getByLabelText("Transcript text");
    fireEvent.change(box, { target: { value: "Frau Albers hat heute starke Schmerzen im Knie. Telefon [TELEFON]." } });
    act(() => { fireEvent.keyDown(window, { key: "Enter", ctrlKey: true }); });
    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.path.endsWith("/submit"))).toBe(true));
    const body = calls.find((c) => c.path.endsWith("/submit"))!.body as { transcript: string; entities: { start: number; end: number }[] };
    const [e] = body.entities;
    expect(body.transcript.slice(e.start, e.end)).toBe("Schmerzen im Knie");
  });

  it("explains why a transcript with a real identifier was refused", async () => {
    setup(() => ({ status: 422, json: { error: "invalid_annotation", message: "possible PHI in the transcript (phone); use the placeholder instead" } }));
    renderWithClient(<Annotate project={project} kind="ANNOTATE" />);
    await userEvent.click(await screen.findByRole("button", { name: /^Submit/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/Not submitted: possible PHI in the transcript \(phone\)/);
  });

  it("says so when the queue is empty", async () => {
    mockApi({ "POST /api/projects/p1/tasks/next": () => ({ status: 204 }), "GET /api/labels": () => ({ json: labels }) });
    renderWithClient(<Annotate project={project} kind="ANNOTATE" />);
    expect(await screen.findByText("You're all caught up")).toBeInTheDocument();
  });
});
