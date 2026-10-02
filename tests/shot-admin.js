// 管理端截图：需要先登录（密码从 .env 读，不打印）
const { connectCDP } = require('/root/.playwright/cdp');
const fs = require('node:fs');
const path = require('node:path');

function adminPassword() {
  const txt = fs.readFileSync('/app/workspace/qqbot/.env', 'utf8');
  for (const line of txt.split('\n')) {
    const t = line.trim();
    if (t.startsWith('ADMIN_PASSWORD=')) return t.slice('ADMIN_PASSWORD='.length).trim();
  }
  throw new Error('没有找到 ADMIN_PASSWORD');
}

(async () => {
  const base = process.env.BASE || 'http://localhost:8080';
  const outDir = '/app/workspace/qqbot/tests/screenshots';
  const browser = await connectCDP();
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1100 } });
  const page = await ctx.newPage();
  await page.goto(base + '/admin/login', { waitUntil: 'networkidle', timeout: 30000 });
  const ok = await page.evaluate(async (pw) => {
    const r = await fetch('/admin/api/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: pw })
    });
    return r.ok;
  }, adminPassword());
  console.log('  登录:', ok ? 'OK' : 'FAIL');
  const targets = [['/admin/kb', 'admin-kb'], ['/admin/settings', 'admin-settings'], ['/library?group=guide', 'guide']];
  for (const [p, name] of targets) {
    await page.goto(base + p, { waitUntil: 'networkidle', timeout: 30000 });
    await page.waitForTimeout(1800);
    await page.screenshot({ path: path.join(outDir, 'v3-' + name + '.png') });
    const h1 = await page.evaluate(() => document.querySelector('h1')?.textContent?.trim() ?? '');
    const len = await page.evaluate(() => document.body.innerText.length);
    console.log('  OK ', p, '→', 'v3-' + name + '.png', '「' + h1 + '」', len + '字');
  }
  await ctx.close();
  await browser.close();
})().catch(e => { console.error('FAIL:', e.message); process.exit(1) });