import { render, screen } from "@testing-library/react";
import { Transcript } from "./Transcript";

describe("Transcript", () => {
  it("renders every character exactly once, with offsets on each segment", () => {
    const text = "RR 130/80, Puls 72. Herr Albers schläft.";
    const { getByTestId } = render(
      <Transcript text={text} spans={[{ start: 0, end: 9, label: "VITAL_SIGN" }]} surrogates={[{ start: 25, end: 31, kind: "PERSON_NAME" }]} />,
    );
    const root = getByTestId("transcript");
    const plain = [...root.querySelectorAll<HTMLElement>("[data-start]")].map((e) => e.textContent).join("");
    expect(plain).toBe(text);
    expect(screen.getByTitle("Vital sign")).toHaveTextContent("RR 130/80");
    expect(screen.getByTitle(/Replaced name/)).toHaveTextContent("Albers");
  });
});
