import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

test('main shell has no critical accessibility violations', async ({ page }) => {
  await page.goto('/');
  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations.filter(v => v.impact === 'critical')).toEqual([]);
});

test('timeline and replay views have no critical accessibility violations', async ({ page }) => {
  await page.goto('/?aggregateId=order-demo-001&seq=8&compare=5&panel=replay');
  await expect(page.getByRole('region', { name: 'State viewer' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Replay debugger' })).toBeVisible();
  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations.filter(v => v.impact === 'critical')).toEqual([]);
});
