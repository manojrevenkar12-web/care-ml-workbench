import { expect, type Page, test } from "@playwright/test";

async function signIn(page: Page, user: string) {
  await page.goto("/login");
  await page.getByRole("button", { name: new RegExp(`^${user}`) }).click();
  await page.waitForURL((u) => !u.pathname.startsWith("/login"));
}

test("an annotator corrects, labels and submits a transcript", async ({ page }) => {
  await signIn(page, "anna");
  await expect(page).toHaveURL(/\/annotate$/);
  const box = page.getByLabel("Transcript text");
  await expect(box).toBeVisible();
  const before = await box.inputValue();
  await box.press("End");
  await box.pressSequentially(" Ruhig.");
  await page.keyboard.press("Escape");

  // select the last word we typed and label it "Mood" with its hotkey
  await page.getByTestId("transcript").evaluate((root) => {
    const seg = [...root.querySelectorAll<HTMLElement>("[data-start]")].reverse().find((e) => e.textContent!.includes("Ruhig"))!;
    const last = seg.firstChild!;
    const t = last.textContent!;
    const r = document.createRange();
    r.setStart(last, t.lastIndexOf("Ruhig"));
    r.setEnd(last, t.lastIndexOf("Ruhig") + 5);
    getSelection()!.removeAllRanges();
    getSelection()!.addRange(r);
  });
  await page.keyboard.press("9");
  await expect(page.getByTestId("transcript").getByTitle("Mood")).toHaveText(/Ruhig/);

  await page.keyboard.press("Control+Enter");
  await expect(page.getByRole("status").filter({ hasText: "Submitted" })).toBeVisible();
  // the next transcript is a different one
  await expect(page.getByLabel("Transcript text").or(page.getByText("You're all caught up"))).toBeVisible();
  expect(await page.getByLabel("Transcript text").inputValue().catch(() => "")).not.toBe(`${before} Ruhig.`);
});

test("typing a real-looking identifier is refused with an explanation", async ({ page }) => {
  await signIn(page, "ben");
  const box = page.getByLabel("Transcript text");
  await box.press("End");
  await box.pressSequentially(" Rückruf unter 0151 2345678.");
  await page.keyboard.press("Control+Enter");
  await expect(page.getByRole("alert")).toContainText("Not submitted: possible PHI in the transcript (phone)");
});

test("a reviewer resolves a disagreement, builds a dataset and trains a model", async ({ page }) => {
  await signIn(page, "rita");
  await page.goto("/p/nord-doku/review");
  await expect(page.getByText(/^Version A by/)).toBeVisible();
  await page.getByRole("button", { name: "Start from this" }).nth(1).click();
  await page.getByRole("button", { name: /Save final version/ }).click();
  await expect(page.getByRole("status").filter({ hasText: "Final version saved" })).toBeVisible();

  await page.goto("/p/nord-doku/datasets");
  await page.getByRole("button", { name: "Create dataset version" }).click();
  await expect(page.getByRole("heading", { name: /^Version \d+$/ }).first()).toBeVisible();
  await page.getByRole("link", { name: "Train on this version" }).first().click();
  await page.getByRole("button", { name: "Start training" }).click();
  await expect(page.locator(".status.SUCCEEDED").first()).toBeVisible({ timeout: 45_000 });

  await page.goto("/p/nord-doku/evaluate");
  await expect(page.getByText("Speech recognition: asr-v2 against asr-v1")).toBeVisible();
  await expect(page.getByText(/^Care-entity tagger: entity-tagger-v\d+$/)).toBeVisible();

  await page.goto("/p/nord-doku/playground");
  await page.getByRole("button", { name: "Find care information" }).click();
  await expect(page.getByText(/entities found by entity-tagger/)).toBeVisible();
});

test("viewing an original record needs a reason and lands in the audit log", async ({ page, browser }) => {
  await signIn(page, "dora");
  await page.goto("/p/nord-doku/explore");
  await page.getByRole("row").nth(1).click();
  const view = page.getByRole("button", { name: "View original" });
  await expect(view).toBeDisabled();
  await page.getByLabel("Reason").fill("Checking a de-identification miss from review");
  await view.click();
  const logged = await page.getByText(/Logged as audit event #\d+/).textContent();
  const seq = logged!.match(/#(\d+)/)![1];

  const admin = await (await browser.newContext()).newPage();
  await signIn(admin, "olivia");
  await admin.goto("/p/nord-doku/audit");
  await expect(admin.getByText(/Chain verified/)).toBeVisible();
  const row = admin.getByRole("row").filter({ hasText: "Checking a de-identification miss from review" });
  await expect(row.first()).toContainText(seq);
  await expect(row.first()).toContainText("dora");
});

test("people only see what their role allows", async ({ page }) => {
  await signIn(page, "anna");
  await expect(page.getByRole("link", { name: "Audit log" })).toHaveCount(0);
  await expect(page.getByRole("link", { name: /^Review/ })).toHaveCount(0);
  await page.goto("/p/nord-doku/explore");
  await page.getByRole("row").nth(1).click();
  await expect(page.getByRole("button", { name: "View original" })).toHaveCount(0);

  await signIn(page, "sam"); // admin of the other tenant
  await expect(page.getByRole("combobox")).not.toContainText("Pflegedokumentation Nord");
});
