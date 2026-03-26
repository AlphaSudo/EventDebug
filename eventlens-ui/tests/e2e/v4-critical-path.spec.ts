import { expect, test } from '@playwright/test';

test('state diff compare mode is shareable by URL', async ({ page }) => {
  await page.goto('/?aggregateId=order-demo-001&seq=100&compare=89&tab=changes&panel=state');

  await expect(page.getByRole('region', { name: 'State viewer' })).toBeVisible();
  await expect(page.getByText('Compared with #89')).toBeVisible();
  await expect(page.getByText('Structural diff')).toBeVisible();
});

test('replay debugger keeps panel and position in URL while stepping', async ({ page }) => {
  await page.goto('/?aggregateId=order-demo-001&seq=5&panel=replay');

  await expect(page.getByRole('region', { name: 'Replay debugger' })).toBeVisible();
  await page.getByRole('button', { name: 'Replay next event' }).click();
  await expect(page).toHaveURL(/seq=6/);
  await expect(page).toHaveURL(/panel=replay/);
});

test('statistics panel renders in demo mode', async ({ page }) => {
  await page.goto('/#/stats');

  await expect(page.getByRole('region', { name: 'Statistics panel' })).toBeVisible();
  await expect(page.getByText('Total events')).toBeVisible();
  await expect(page.getByText('100')).toBeVisible();
});

test('command palette opens aggregate and keyboard navigation advances the selection', async ({ page }) => {
  await page.goto('/');

  await page.keyboard.press('Control+K');
  await expect(page.getByRole('dialog', { name: 'Command palette' })).toBeVisible();
  await page.getByLabel('Command palette search').fill('demo');
  await page.keyboard.press('Enter');

  await expect(page).toHaveURL(/aggregateId=order-demo-001/);
  await expect(page.getByText('Viewing:')).toBeVisible();

  await page.keyboard.press('j');
  await expect(page).toHaveURL(/seq=2/);
});

test('timeline stays virtualized while still allowing direct jumps', async ({ page }) => {
  await page.goto('/?aggregateId=order-demo-001&seq=1');

  const timelineButtons = page.locator('.timeline-stepper button');
  expect(await timelineButtons.count()).toBeLessThan(40);

  const jumpInput = page.getByLabel('Jump to sequence number');
  await jumpInput.fill('100');
  await jumpInput.press('Enter');

  await expect(page).toHaveURL(/seq=100/);
  await expect(page.getByText('Selected seq #100')).toBeVisible();
});
