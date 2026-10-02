// 用真机 Chrome 截图公开站与管理后台（不干扰用户已有标签页）
const { connectCDP, resolveHostIp } = require('/root/.playwright/cdp');
const path = require('node:path');

(async () => {
  const host = resolveHostIp();
  const base = process.env.BASE || `http://${host}:5173`;
  const outDir = process.env.OUT || '/app/workspace/qqbot/tests/screenshots';
  require('node:fs').mkdirSync(outDir, { recursive: true });

  const browser = await connectCDP();
  const ctx = browser.contexts()[0];
  // ★ 新建页面，不动用户已有的标签
  const page = await ctx.newPage();
  await page.setViewportSize({ width: 1440, height: 900 });

  const targets = [
    ['/', 'public-home'],
    ['/library', 'public-library'],
    ['/plaza', 'public-plaza'],
    ['/library/manage', 'public-manage'],
    ['/about', 'public-about'],
  ];

  for (const [p, name] of targets) {
    try {
      await page.goto(base + p, { waitUntil: 'networkidle', timeout: 30000 });
      await page.waitForTimeout(1200);
      const file = path.join(outDir, name + '.png');
      await page.screenshot({ path: file, fullPage: false });
      // 顺便抓标题和是否有报错
      const info = await page.evaluate(() => ({
        title: document.title,
        h1: document.querySelector('h1')?.textContent?.trim() ?? '',
        bodyLen: document.body.innerText.length,
      }));
      console.log(`  OK  ${p.padEnd(18)} → ${name}.png  「${info.h1 || info.title}」`);
    } catch (e) {
      console.log(`  ERR ${p} → ${e.message.slice(0, 80)}`);
    }
  }

  await page.close();
  await browser.close();
  console.log('\n  截图目录:', outDir);
})().catch(e => { console.error('FAIL:', e.message); process.exit(1) });
