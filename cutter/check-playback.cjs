// Run against a disposable cutter server with a >= 90-second test recording:
// PLAYWRIGHT_MODULE=/path/to/playwright XHCUT_TEST_URL=http://localhost:18092 node cutter/check-playback.cjs
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
        await page.addInitScript(() => {
            let Hls;
            window.hlsErrors = [];
            Object.defineProperty(window, 'Hls', { configurable: true, get: () => Hls, set: value => {
                Hls = new Proxy(value, { construct(target, args) {
                    const h = new target(...args);
                    h.on(value.Events.ERROR, (_, d) => {
                        if (d.fatal || d.details === 'bufferAppendNoProgress') window.hlsErrors.push(d.details);
                    });
                    return h;
                } });
            } });
        });
        if (process.env.XHCUT_TEST_HTML) await page.route(base + '/', r => r.fulfill({
            contentType: 'text/html', body: fs.readFileSync(process.env.XHCUT_TEST_HTML, 'utf8')
        }));
        await page.goto(base, { waitUntil: 'networkidle' });
        const item = await page.evaluate(async () => {
            const data = await (await fetch('/api/library?limit=300')).json();
            return data.items.find(x => x.durationSeconds >= 90 && !x.hasEvents);
        });
        assert(item, 'Test server needs a recording of at least 90 seconds without events');
        await page.evaluate(async item => {
            window.testApp = document.querySelector('#app').__vue_app__._instance.setupState;
            await testApp.open(item);
        }, item);
        await page.waitForFunction(() => document.querySelector('video').readyState >= 2);
        const state = () => page.evaluate(() => {
            const v = document.querySelector('video');
            return { ui: testApp.currentTime, video: v.currentTime, paused: v.paused, seeking: testApp.seeking,
                overlay: !!testApp.scrubFrameUrl, duration: testApp.detail.durationSeconds,
                buffer: Array.from({ length: v.buffered.length }, (_, i) => [v.buffered.start(i), v.buffered.end(i)]) };
        });
        const seek = async fraction => {
            const box = await page.locator('.scrub-zone').boundingBox();
            await page.mouse.click(box.x + box.width * fraction, box.y + box.height / 2);
            return (await state()).ui;
        };
        const settled = target => page.waitForFunction(t => {
            const v = document.querySelector('video');
            return !testApp.seeking && !v.seeking && Math.abs(v.currentTime - t) < 1;
        }, target, { timeout: 30000 });
        const play = () => page.getByTitle('播放 (空格)', { exact: true }).click();
        const pause = () => page.getByTitle('暂停 (空格)', { exact: true }).click();

        await play();
        await page.waitForTimeout(8500);
        assert((await state()).video > 7, 'Playback must cross multiple HLS fragments');
        await pause();
        console.log('PASS continuous playback', await state());

        // Hold network responses longer than the old three-second timeout.
        let release;
        const gate = new Promise(r => { release = r; });
        let hold = true;
        await page.route('**/preview/*.ts?*', async r => { if (hold) await gate; await r.continue(); });
        let target = await seek(0.8);
        await play();
        await page.waitForTimeout(3500);
        assert(Math.abs((await state()).ui - target) < 0.1, 'Waiting must retain the requested position');
        await pause(); // The pause control must work even before a playing event.
        assert((await state()).paused);
        // A newer seek replaces the in-flight target.
        target = await seek(0.55);
        hold = false;
        release();
        await settled(target);
        assert((await state()).paused, 'A paused seek must remain paused');
        await page.waitForTimeout(1000);
        assert(!(await state()).overlay, 'Late thumbnail responses must not cover settled video');
        console.log('PASS slow and superseded seek', await state());

        await play();
        await page.waitForTimeout(7000);
        assert((await state()).video > target + 5, 'Playback must continue from the unbuffered target');
        const ruler = await page.locator('.scrub-zone').boundingBox();
        await page.mouse.move(ruler.x + ruler.width * 0.4, ruler.y + ruler.height / 2);
        await page.mouse.down();
        await page.mouse.move(ruler.x + ruler.width * 0.25, ruler.y + ruler.height / 2, { steps: 20 });
        await page.waitForTimeout(150);
        await page.mouse.up();
        target = (await state()).ui;
        await settled(target);
        await page.waitForTimeout(1500);
        assert(!(await state()).paused && (await state()).video > target + 0.5, 'Scrubbing during playback must resume');
        await pause();
        target = (await state()).ui;
        await page.getByRole('button', { name: 'tiny', exact: true }).click();
        await settled(target);
        assert((await state()).paused, 'Quality changes retain paused state');
        await play();
        await page.waitForTimeout(1500);
        target = (await state()).ui;
        await page.getByRole('button', { name: 'low', exact: true }).click();
        await settled(target);
        await page.waitForTimeout(1500);
        assert(!(await state()).paused, 'Quality changes retain playback intent');
        console.log('PASS scrubbing and quality changes', await state());
        await pause();

        // Select a destination while the replacement source has no metadata yet.
        let releaseManifest;
        const manifestGate = new Promise(r => { releaseManifest = r; });
        await page.route('**/preview.m3u8?*', async r => { await manifestGate; await r.continue(); });
        await page.getByRole('button', { name: 'tiny', exact: true }).click();
        target = await seek(0.7);
        await page.waitForTimeout(500);
        assert(Math.abs((await state()).ui - target) < 0.1);
        releaseManifest();
        await settled(target);
        assert((await state()).paused);
        console.log('PASS seeking before metadata', await state());

        // The fixture is faststart H.264/AAC, so exercise native Range seeking too.
        if (process.env.XHCUT_TEST_RAW === '1') {
            target = (await state()).ui;
            await page.getByRole('button', { name: '无损', exact: true }).click();
            await settled(target);
            target = await seek(0.85);
            await settled(target);
            await play();
            await page.waitForTimeout(2000);
            assert((await state()).video > target + 1);
            console.log('PASS native Range playback', await state());
        }
        assert.deepEqual(errors, [], 'No browser runtime errors');
        assert.deepEqual(await page.evaluate(() => hlsErrors), [], 'No fatal HLS or overlapping buffer errors');
        console.log('All Chrome playback checks passed.');
    } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
