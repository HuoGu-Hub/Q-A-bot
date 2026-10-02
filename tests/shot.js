// 截图：用真机 Chrome 看实际页面
const { connectCDP } = require('/root/.playwright/cdp');
const path = require('node:path');
const fs = require('node:fs');

(async () => {
  const base = process.env.BASE || 'http://localhost:8080';
  const tag = process.env.TAG || 'p';
  const outDir = '/app/workspace/qqbot/tests/screenshots';
  fs.mkdirSync(outDir, { recursive: true });

  const browser = await connectCDP();
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await ctx.newPage();

  const targets = JSON.parse(process.env.TARGETS || '[]');

  for (const [p, name] of targets) {
    try {
      await page.goto(base + p, { waitUntil: 'networkidle', timeout: 30000 });
      await page.waitForTimeout(1600);
      await page.screenshot({ path: path.join(outDir, tag + '-' + name + '.png'), fullPage: false });
      const info = await page.evaluate(() => ({
        kids: document.getElementById('app')?.children.length ?? -1,
        h1: document.querySelector('h1')?.textContent?.trim() ?? '',
        textLen: document.body.innerText.length,
      }));
      console.log(`  ${info.kids > 0 ? 'OK ' : 'ERR'} ${p.padEnd(40)} ${tag}-${name}.png  「${info.h1}」 ${info.textLen}字`);
    } catch (e) {
      console.log(`  ERR ${p} → ${e.message.slice(0, 60)}`);
    }
  }

  await ctx.close();
  await browser.close();
})().catch(e => { console.error('FAIL:', e.message); process.exit(1) });
