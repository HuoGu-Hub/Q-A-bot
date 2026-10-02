// 用真机 Chrome 截图【生产版】（宿主机 8080）
const { connectCDP } = require('/root/.playwright/cdp');
const path = require('node:path');
const fs = require('node:fs');

(async () => {
  const base = process.env.BASE || 'http://localhost:8080';
  const outDir = process.env.OUT || '/app/workspace/qqbot/tests/screenshots';
  fs.mkdirSync(outDir, { recursive: true });

  const browser = await connectCDP();
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();

  const targets = [
    ['/', 'prod-home'],
    ['/library', 'prod-library'],
    ['/plaza', 'prod-plaza'],
    ['/library/manage', 'prod-manage'],
    ['/about', 'prod-about'],
  ];

  for (const [p, name] of targets) {
    try {
      await page.goto(base + p, { waitUntil: 'networkidle', timeout: 30000 });
      await page.waitForTimeout(1500);
      await page.screenshot({ path: path.join(outDir, name + '.png'), fullPage: false });
      const info = await page.evaluate(() => ({
        appKids: document.getElementById('app')?.children.length ?? -1,
        h1: document.querySelector('h1')?.textContent?.trim() ?? '',
        nav: [...document.querySelectorAll('header a')].map(a => a.textContent.trim()).join(' / '),
        textLen: document.body.innerText.length,
      }));
      const mark = info.appKids > 0 ? 'OK ' : 'ERR';
      console.log(`  ${mark} ${p.padEnd(18)} → ${name}.png  标题「${info.h1}」  导航[${info.nav}]  ${info.textLen} 字`);
    } catch (e) {
      console.log(`  ERR ${p} → ${e.message.slice(0, 70)}`);
    }
  }

  await ctx.close();
  await browser.close();
  console.log();
  console.log('  截图目录:', outDir);
})().catch(e => { console.error('FAIL:', e.message); process.exit(1) });
