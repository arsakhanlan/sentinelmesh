import { test, expect } from "@playwright/test";

test.describe("SOC shell", () => {
  test("home loads TopBar and adversary UI", async ({ page }) => {
    await page.goto("/");

    await expect(page.getByText("SentinelMesh")).toBeVisible();
    await expect(page.getByRole("link", { name: /policy lab/i })).toBeVisible();
    await expect(page.getByRole("link", { name: /^soc$/i })).toBeVisible();

    // Adversary console heading / panel
    await expect(page.getByText("Adversary Console")).toBeVisible();

    // Scenarios load from agents API — wait briefly, then click first scenario if present.
    const loading = page.getByText("Loading scenarios…");
    try {
      await loading.waitFor({ state: "detached", timeout: 12_000 });
    } catch {
      /* backend/agents may be down in minimal CI — still assert shell above */
    }

    const scenarioBtn = page
      .getByRole("button")
      .filter({ hasText: /injection|credential|phishing|payment|workflow/i })
      .first();
    if ((await scenarioBtn.count()) > 0) {
      await scenarioBtn.click();
      await expect(page.getByText(/^Fired:/i)).toBeVisible({ timeout: 8000 });
    }
  });

  test("policy lab omits firehose pill", async ({ page }) => {
    await page.goto("/policies");
    await expect(page.getByText("SentinelMesh")).toBeVisible();
    await expect(page.locator("header").first()).not.toContainText("Firehose");
  });
});
