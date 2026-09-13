// Uses the same disposable server and Playwright setup as check-playback.cjs.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const base = process.env.XHCUT_TEST_URL || 'http://localhost:18092';

(async () => {
    const browser = await chromium.launch({ channel: 'chrome', headless: true });
    try {
        const page = await browser.newPage({ viewport: { width: 1500, height: 1000 } });
        const errors = [];
        page.on('pageerror', e => errors.push(e.message));
        if (process.env.XHCUT_TEST_HTML) await page.route(url => url.origin === new URL(base).origin && url.pathname === '/', r => r.fulfill({
            contentType: 'text/html', body: fs.readFileSync(process.env.XHCUT_TEST_HTML, 'utf8')
        }));
        await page.goto(base, { waitUntil: 'networkidle' });
        const item = await page.evaluate(async () => (await (await fetch('/api/library?limit=300')).json()).items.find(x => x.durationSeconds >= 90 && !x.hasEvents));
        assert(item);
        const expose = () => page.evaluate(() => { window.editor = document.querySelector('#app').__vue_app__._instance.setupState; });
        await expose();
        await page.evaluate(item => editor.open(item), item);
        await page.waitForFunction(() => !editor.editorLoading && !editor.seeking);
        const state = () => page.evaluate(() => ({ segments: JSON.parse(JSON.stringify(editor.segments)), active: editor.activeSeg,
            time: editor.currentTime, rate: editor.rate, paused: document.querySelector('video').paused,
            layout: { ...editor.layout }, heights: { ...editor.laneHeights }, zoom: editor.zoom, tab: editor.rightTab,
            scroll: editor.viewport.scrollLeft, video: document.querySelector('video').currentTime }));
        const go = async t => {
            const box = await page.locator('[data-lane="ruler"]').boundingBox();
            const duration = await page.evaluate(() => editor.duration);
            await page.mouse.click(box.x + box.width * t / duration, box.y + 10);
            await page.waitForFunction(t => Math.abs(editor.currentTime - t) < 0.05, t);
        };
        const key = k => page.keyboard.press(k);
        const bounds = async () => (await state()).segments.map(s => [s.start, s.end]);
        const close = (actual, expected) => assert(Math.abs(actual - expected) < 0.05, `${actual} != ${expected}`);

        await page.waitForFunction(() => [...document.querySelectorAll('.thumbnail img')].filter(i => i.complete && i.naturalWidth).length >= 2);
        assert(await page.locator('.thumbnail').count() <= 32);
        console.log('PASS visible timeline thumbnails');

        // Keep the mouse down: video fragments and blue cache bars must update now.
        const ruler = await page.locator('[data-lane="ruler"]').boundingBox();
        await page.mouse.move(ruler.x + ruler.width * 0.7, ruler.y + 10);
        await page.mouse.down();
        await page.waitForFunction(() => {
            const v = document.querySelector('video');
            return !v.seeking && Math.abs(v.currentTime - editor.currentTime) < 0.25 && v.readyState >= 2 && editor.bufferedMarks.some(b => b.left > 30);
        }, null, { timeout: 30000 });
        await page.mouse.move(ruler.x + ruler.width * 0.8, ruler.y + 10, { steps: 10 });
        await page.waitForFunction(() => !document.querySelector('video').seeking && Math.abs(document.querySelector('video').currentTime - editor.currentTime) < 0.25);
        await page.mouse.up();
        console.log('PASS video fragments and cache bars while dragging');

        // A focused button must not receive a synthetic click from Space.
        await page.getByTitle('新增片段', { exact: true }).focus();
        await key('Space');
        await page.waitForFunction(() => !document.querySelector('video').paused);
        assert.equal((await state()).segments.length, 0);
        await key('Space');
        await page.waitForFunction(() => document.querySelector('video').paused);
        await key('l'); assert((await state()).rate > 1);
        await key('k'); assert.equal((await state()).rate, 1);
        await key('j'); assert((await state()).rate < 1);
        await key('k');
        console.log('PASS captured Space and JKL shortcuts on focused buttons');

        await go(12); await key('i');
        await go(18); await page.getByTitle('新增片段', { exact: true }).click();
        assert.equal((await state()).segments.length, 1); close((await bounds())[0][0], 18);
        await go(10); await key('o'); assert.equal((await bounds())[0][1], null);
        await go(30); await key('o'); close((await bounds())[0][1], 30);
        await go(20); await key('i'); close((await bounds())[0][0], 20);
        await go(50); await page.getByTitle('新增片段', { exact: true }).click();
        await go(60); await page.getByTitle('新增片段', { exact: true }).click();
        assert.equal((await state()).segments.length, 2);
        await go(80); await key('o');
        assert.deepEqual((await bounds()).map(b => b.map(Math.round)), [[20, 30], [60, 80]]);
        console.log('PASS moving a single pending marker and I/O editing');

        // Numeric fields reject both reversed bounds and overlaps without clobbering.
        const end = page.getByLabel('片段结束时间').nth(0);
        await end.fill('70'); await end.press('Tab'); close((await bounds())[0][1], 30);
        await end.fill('10'); await end.press('Tab'); close((await bounds())[0][1], 30);
        const start = page.getByLabel('片段开始时间').nth(1);
        await start.fill('-1'); await start.press('Tab'); close((await bounds())[1][0], 60);
        await go(25); await page.getByTitle('新增片段', { exact: true }).click();
        assert.equal((await state()).segments.length, 2);
        // Clip drag also goes through the overlap guard.
        const clip = await page.locator('.clip').nth(0).boundingBox();
        await page.mouse.move(clip.x + clip.width / 2, clip.y + clip.height / 2);
        await page.mouse.down();
        await page.mouse.move(clip.x + clip.width / 2 + ruler.width * 40 / 120, clip.y + clip.height / 2);
        await page.mouse.up();
        close((await bounds())[0][0], 20); close((await bounds())[1][0], 60);
        console.log('PASS boundary and overlap guards for input, markers and clip drag');

        await go(40); await page.getByTitle('新增片段', { exact: true }).click();
        await go(50); await key('o');
        await page.getByLabel('片段开始时间').nth(1).locator('..').locator('input[type="checkbox"]').uncheck();
        await page.getByRole('button', { name: '合并勾选', exact: true }).click();
        assert.equal((await state()).segments.length, 3, 'Merge must preserve the unchecked intervening segment');
        await key('Delete');
        assert.equal((await state()).segments.length, 2);
        await page.getByRole('button', { name: '合并勾选', exact: true }).click();
        assert.equal((await state()).segments.length, 1);
        assert.deepEqual((await bounds()).map(b => b.map(Math.round)), [[20, 80]]);
        await page.getByRole('button', { name: '撤销', exact: true }).click();
        assert.equal((await state()).segments.length, 2);
        await page.getByRole('button', { name: '重做', exact: true }).click();
        assert.equal((await state()).segments.length, 1);
        console.log('PASS merge, undo and redo');

        const name = page.getByPlaceholder('名称', { exact: true });
        await name.fill(''); await name.pressSequentially('jkl i o ');
        assert.equal((await state()).segments[0].name, 'jkl i o ');
        assert.equal((await state()).rate, 1);

        const resize = async (label, dx, dy) => {
            const b = await page.getByRole('separator', { name: label, exact: true }).boundingBox();
            await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2); await page.mouse.down();
            await page.mouse.move(b.x + b.width / 2 + dx, b.y + b.height / 2 + dy); await page.mouse.up();
        };
        const before = await state();
        await resize('调整文件面板宽度', 35, 0);
        await resize('调整当前标签页面板宽度', -40, 0);
        await resize('调整预览高度', 0, -35);
        await resize('调整片段轨道高度', 0, 24);
        await resize('调整缩略图轨道高度', 0, 20);
        const resized = await state();
        close(resized.layout.library, before.layout.library + 35);
        close(resized.layout.segments, before.layout.segments + 40);
        close(resized.heights.clips, before.heights.clips + 24);
        await page.getByRole('button', { name: '建议', exact: true }).click();
        await resize('调整当前标签页面板宽度', -20, 0);
        close((await state()).layout.suggest, before.layout.suggest + 20);
        await page.getByRole('button', { name: /片段 1/ }).click();
        close((await state()).layout.segments, resized.layout.segments);
        console.log('PASS panel and individual track resizing');

        await page.getByLabel('播放速度').selectOption('2');
        await page.getByRole('button', { name: '×4', exact: true }).click();
        await page.locator('[data-panel="timeline"]').evaluate(el => { el.scrollLeft = 200; });
        await page.waitForTimeout(600);
        const saved = await state();
        console.log('Saved viewport', saved.scroll, await page.evaluate(() => JSON.parse(localStorage.getItem('xhcut:editor:v1:' + editor.selected.id))?.scroll));
        await page.reload({ waitUntil: 'networkidle' }); await expose();
        await page.waitForFunction(() => editor.detail && !editor.editorLoading && !editor.seeking);
        const restored = await state();
        console.log('Restored viewport', restored.scroll, await page.evaluate(() => JSON.parse(localStorage.getItem('xhcut:editor:v1:' + editor.selected.id))?.scroll));
        await page.screenshot({ path: 'build/playback-check/editor.png' });
        assert.deepEqual(restored.segments, saved.segments);
        assert.deepEqual(restored.layout, saved.layout); assert.deepEqual(restored.heights, saved.heights);
        assert.equal(restored.active, saved.active); assert.equal(restored.tab, saved.tab);
        assert.equal(restored.rate, 2); assert.equal(restored.zoom, saved.zoom);
        close(restored.time, saved.time); close(restored.scroll, saved.scroll);
        console.log('PASS refresh restores the current unsaved editor and layout');
        await page.screenshot({ path: 'build/playback-check/editor.png' });

        const other = await page.evaluate(async id => (await (await fetch('/api/library?limit=300')).json()).items.find(x => x.id !== id && x.durationSeconds >= 90 && !x.hasEvents), item.id);
        if (other) {
            await page.evaluate(item => editor.open(item), other);
            await go(6); await key('i');
            await page.reload({ waitUntil: 'networkidle' }); await expose();
            await page.waitForFunction(() => editor.detail && !editor.editorLoading && !editor.seeking);
            await go(8); await page.getByTitle('新增片段', { exact: true }).click();
            assert.equal((await state()).segments.length, 1);
            close((await bounds())[0][0], 8);
            assert.equal((await bounds())[0][1], null);
            await page.evaluate(item => editor.open(item), item);
            assert.deepEqual((await state()).segments, saved.segments);
            close((await state()).scroll, saved.scroll);

            // An old metadata response must never replace a newly selected editor.
            let release;
            const gate = new Promise(r => { release = r; });
            await page.route(base + '/api/media/' + other.id, async r => { await gate; await r.continue(); });
            await page.evaluate(item => { window.lateOpen = editor.open(item); }, other);
            await page.evaluate(item => editor.open(item), item);
            release(); await page.evaluate(() => window.lateOpen);
            assert.equal(await page.evaluate(() => editor.selected.id), item.id);
            assert.deepEqual((await state()).segments, saved.segments);
            console.log('PASS per-recording drafts, pending marker restoration and rapid file switching');
        }
        assert.deepEqual(errors, []);
        console.log('All Chrome editor checks passed.');
    } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
