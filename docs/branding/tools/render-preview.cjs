const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

const root = path.resolve(__dirname, '..');
const candidates = [process.env.BRAND_BROWSER, chromium.executablePath(), 'C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'];
const executablePath = candidates.find(candidate => candidate && fs.existsSync(candidate));
if (!executablePath) throw new Error('Set BRAND_BROWSER to an installed Chromium-family browser.');

async function main() {
  fs.mkdirSync(path.join(root, 'previews'), { recursive: true });
  const browser = await chromium.launch({ executablePath, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1 });
  const failures = [];
  page.on('pageerror', error => failures.push(error.message));
  page.on('requestfailed', request => failures.push(request.url() + ': ' + request.failure().errorText));
  const external = [];
  page.on('request', request => { if (/^https?:/.test(request.url())) external.push(request.url()); });
  await page.goto(pathToFileURL(path.join(root, 'brand-preview.html')).href);
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: path.join(root, 'previews/brand-desktop.png'), fullPage: true });
  await page.screenshot({ path: path.join(root, 'previews/brand-cover.png'), fullPage: false });
  const desktop = await inspect(page);
  await page.getByRole('button', { name: 'Show dark UI', exact: true }).click();
  if (await page.locator('#theme-toggle').getAttribute('aria-pressed') !== 'true') throw new Error('Theme toggle did not update aria-pressed.');
  await page.locator('#phone').screenshot({ path: path.join(root, 'previews/ui-dark.png') });
  await page.getByRole('button', { name: 'Show light UI', exact: true }).click();
  await page.locator('#phone').screenshot({ path: path.join(root, 'previews/ui-light.png') });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(root, 'previews/brand-mobile.png'), fullPage: true });
  await page.screenshot({ path: path.join(root, 'previews/mobile-cover.png'), fullPage: false });
  const mobile = await inspect(page);
  await page.setViewportSize({ width: 320, height: 800 });
  const narrow = await inspect(page);
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const reducedMotion = await page.evaluate(() => getComputedStyle(document.documentElement).scrollBehavior);
  const report = { desktop, mobile, narrow, reducedMotion, failures, externalRequests: external };
  fs.writeFileSync(path.join(root, 'previews/browser-check.json'), JSON.stringify(report, null, 2) + '\n');
  await browser.close();
  console.log(JSON.stringify(report, null, 2));
  if (failures.length || external.length || [desktop, mobile, narrow].some(v => v.horizontalOverflow || v.brokenImages.length || !v.fontsLoaded)) process.exitCode = 1;
}
async function inspect(page) {
  return page.evaluate(() => ({
    width: innerWidth,
    horizontalOverflow: document.documentElement.scrollWidth > innerWidth,
    brokenImages: [...document.images].filter(img => !img.complete || !img.naturalWidth).map(img => img.getAttribute('src')),
    fontsLoaded: document.fonts.check('800 40px "Bricolage Grotesque"') && document.fonts.check('400 16px Manrope'),
    h1Count: document.querySelectorAll('h1').length,
    imageCount: document.images.length
  }));
}
main().catch(error => { console.error(error); process.exitCode = 1; });
