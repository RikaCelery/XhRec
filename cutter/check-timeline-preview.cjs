// Chrome checks for the timeline's progressive frame preview.
//
// Uses the same disposable server as check-editor.cjs:
//   node cutter/build/libs/cutter-all.jar --port 18092 --media ... --cache ... --out ...
//   PLAYWRIGHT_MODULE=/path/to/playwright XHCUT_TEST_HTML=cutter/src/main/resources/cutter.html \
//     node cutter/check-timeline-preview.cjs
//
// What it pins down:
//   * the thumbnail lane subdivides level by level instead of guessing a bucket size;
//   * a live pointer produces at most one hover request at a time, and a position whose
//     frame is already cached paints without waiting for the network;
//   * the preview popup exists only over the thumbnail lane;
//   * the unsaved-data exit guard fires on an edit and not otherwise.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const zlib = require('node:zlib');
const base = process.env.XHCUT_TEST_URL || 'http://localhost:18092';

/**
 * The pixels of a screenshot, decoded far enough to be probed.
 *
 * Playwright hands back a PNG, and the checks below need to know what colour a handful
 * of pixels actually are — whether the picture reaches the edge of its cell is a fact
 * about paint, not about layout, and every geometric probe measures the box that is
 * filled whether or not anything was painted into it. A full image library would be a
 * dependency this repo does not take; `zlib` and the five scanline filters are enough
 * for the one truecolour case Chrome produces.
 */
function pngPixels(buffer) {
    assert.equal(buffer.toString('ascii', 1, 4), 'PNG', 'not a PNG screenshot');
    let at = 8, width = 0, height = 0, depth = 0, color = 0;
    const idat = [];
    while (at + 8 <= buffer.length) {
        const len = buffer.readUInt32BE(at);
        const type = buffer.toString('ascii', at + 4, at + 8);
        const body = buffer.subarray(at + 8, at + 8 + len);
        if (type === 'IHDR') {
            width = body.readUInt32BE(0); height = body.readUInt32BE(4);
            depth = body[8]; color = body[9];
            assert.equal(body[12], 0, 'interlaced screenshots are not supported');
        } else if (type === 'IDAT') idat.push(body);
        else if (type === 'IEND') break;
        at += 12 + len;
    }
    assert.equal(depth, 8, `unexpected screenshot bit depth ${depth}`);
    const channels = color === 6 ? 4 : color === 2 ? 3 : 0;
    assert(channels, `unexpected screenshot colour type ${color}`);
    const raw = zlib.inflateSync(Buffer.concat(idat));
    const stride = width * channels;
    const out = Buffer.alloc(stride * height);
    const zero = Buffer.alloc(stride);
    for (let y = 0, p = 0; y < height; y++) {
        const filter = raw[p++];
        const line = raw.subarray(p, p + stride); p += stride;
        const prior = y ? out.subarray((y - 1) * stride, y * stride) : zero;
        const row = out.subarray(y * stride, (y + 1) * stride);
        for (let x = 0; x < stride; x++) {
            const a = x >= channels ? row[x - channels] : 0;
            const b = prior[x];
            const c = x >= channels ? prior[x - channels] : 0;
            let v = line[x];
            if (filter === 1) v += a;
            else if (filter === 2) v += b;
            else if (filter === 3) v += (a + b) >> 1;
            else if (filter === 4) {
                const q = a + b - c;
                const pa = Math.abs(q - a), pb = Math.abs(q - b), pc = Math.abs(q - c);
                v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
            } else assert.equal(filter, 0, `unknown scanline filter ${filter}`);
            row[x] = v & 0xff;
        }
    }
    return { width, height, channels, data: out };
}

/**
 * How many pixels differ between two screenshots of the same box.
 *
 * Probed instead of compared against a magic colour, because the background a leak
 * reveals is chosen by the probe: any pixel whose colour *changes* when that background
 * does is a pixel the picture did not paint. Assuming a colour instead would call a
 * magenta frame a hole — which is exactly what this test video's colour bars are.
 */
function paintedLeak(a, b) {
    const pa = pngPixels(a), pb = pngPixels(b);
    assert.equal(pa.width, pb.width, 'the two probes cover different boxes');
    assert.equal(pa.height, pb.height, 'the two probes cover different boxes');
    let n = 0;
    for (let i = 0; i + pa.channels <= pa.data.length; i += pa.channels) {
        if (Math.abs(pa.data[i] - pb.data[i]) > 24
            || Math.abs(pa.data[i + 1] - pb.data[i + 1]) > 24
            || Math.abs(pa.data[i + 2] - pb.data[i + 2]) > 24) n++;
    }
    return n;
}

(async () => {
    const browser = await chromium.launch({ channel: 'chrome', headless: true });
    try {
        const page = await browser.newPage({ viewport: { width: 1500, height: 1000 } });
        const errors = [];
        page.on('pageerror', e => errors.push(e.message));
        if (process.env.XHCUT_TEST_HTML) await page.route(url => url.origin === new URL(base).origin && url.pathname === '/', r => r.fulfill({
            contentType: 'text/html', body: fs.readFileSync(process.env.XHCUT_TEST_HTML, 'utf8')
        }));
        // `domcontentloaded` rather than `networkidle`: the editor keeps frame requests
        // in flight by design, so "the network is quiet" is not a state this page has.
        await page.goto(base, { waitUntil: 'domcontentloaded' });
        const item = await page.evaluate(async () => (await (await fetch('/api/library?limit=300')).json()).items.find(x => x.durationSeconds >= 90));
        assert(item, 'the disposable library needs a recording of at least 90 s');
        const expose = () => page.evaluate(() => { window.editor = document.querySelector('#app').__vue_app__._instance.setupState; });
        await expose();
        // With every frame held open, the lane stays in the state it is in *before* any
        // response lands — the only moment its fill plan is observable at all. It is
        // released below, once the plan has been read.
        const heldFrames = [];
        await page.route(base + '/api/media/**/frame*', route => { heldFrames.push(route) });
        await page.evaluate(item => editor.open(item), item);
        await page.waitForFunction(() => !editor.editorLoading && !editor.seeking);
        await page.waitForFunction(() => window.editor.thumbnailPlan.length > 0, null, { timeout: 30000 })
            .catch(() => { throw new Error(`the lane never planned anything (${heldFrames.length} frames held)`) });
        const coldPlan = await page.evaluate(() => [...new Set(editor.thumbnailPlan.map(c => c.level))].sort((a, b) => b - a));
        await page.unroute(base + '/api/media/**/frame*');
        for (const route of heldFrames.splice(0)) await route.continue().catch(() => {});

        // --- the lane subdivides, level by level ------------------------------
        const target = await page.evaluate(() => editor.thumbnailTarget);
        assert(target > 0, `zoom 1 should subdivide past the cache bucket, got level ${target}`);
        // The rungs step two levels at a time from the target towards the fill floor; the
        // last hop may be a single level, because that floor is a pixel width, not a rung.
        const rungsOk = levels => levels.every((l, i) => {
            if (i === 0) return true
            const gap = levels[i - 1] - l
            return gap === 2 || (gap === 1 && i === levels.length - 1)
        });
        const describe = () => page.evaluate(() => {
            const cells = editor.thumbnailCells
            return {
                zoom: editor.zoom, pps: editor.pxPerSecond, target: editor.thumbnailTarget,
                rungs: [...new Set(cells.map(c => c.level))].sort((a, b) => b - a),
                plan: [...new Set(editor.thumbnailPlan.map(c => c.level))].sort((a, b) => b - a),
                widths: cells.map(c => +( (c.end - c.t) * editor.pxPerSecond ).toFixed(1)),
                cached: editor.thumbnailProgress.filter(p => p[2] > 0).map(p => p[0]),
            }
        });
        const waitFor = async (expression, label, timeout = 60000) => {
            try { await page.waitForFunction(expression, null, { timeout }); }
            catch { throw new Error(`${label} — state: ${JSON.stringify(await describe())}`); }
        };
        // The refinement has to actually reach the level the lane is targeting.
        await waitFor(new Function(`return editor.thumbnailLevel(${target}) > 0`), `level ${target} never filled`);
        await waitFor(() => editor.thumbnailCells.some(c => c.level === editor.thumbnailTarget), 'the target level never got drawn');
        const state = await describe();
        assert(state.cached.includes(target), `the target level must produce frames, cached so far: ${JSON.stringify(state.cached)}`);

        // Everything drawn is a rung of the ladder — no cached level from an earlier zoom
        // may be adopted as a layer, because that is what paves the strip with slivers —
        // and every cell is wide enough to be a picture rather than an edge.
        assert(state.rungs.includes(target), `the target level must be drawn, got ${JSON.stringify(state.rungs)}`);
        assert(rungsOk(state.rungs), `the drawn rungs must step two levels at a time, got ${JSON.stringify(state.rungs)}`);
        for (const px of state.widths) {
            assert(px >= state.pps * 0.1 - 0.01, `a cell is ${px}px wide, which is a sliver`);
            assert(px <= 400, `a cell is ${px}px wide, far wider than a thumbnail`);
        }
        console.log(`PASS lane subdivided to level ${target} (cells ${Math.min(...state.widths)}-${Math.max(...state.widths)}px)`);

        // With nothing cached yet, the plan has to reach below the target for fill: those
        // coarser rungs are what hold a picture where the target has not landed, instead of
        // a gap that grows as the responses come back.
        assert(coldPlan.includes(target), `a cold plan must ask for the target level ${target}, got ${JSON.stringify(coldPlan)}`);
        assert(coldPlan[0] > target, `a cold plan must reach coarser than its target, got ${JSON.stringify(coldPlan)}`);
        assert(rungsOk(coldPlan), `the planned rungs must step two levels at a time, got ${JSON.stringify(coldPlan)}`);
        console.log(`PASS a cold lane planned fill rungs ${JSON.stringify(coldPlan)}`);

        // More than one rung is on screen at once: that is what "refining" means, as
        // opposed to a single pass at whatever bucket size was guessed.
        const drawn = await page.evaluate(() => editor.thumbnailCells.reduce((a, c) => { a[c.level] = (a[c.level] || 0) + 1; return a }, {}));
        assert(await page.locator('.thumbnail').count() <= 1200, 'the lane must not put an unbounded number of cells in the DOM');
        // Labels only where they fit: a strip of overlapping timecodes is not readable.
        const overfull = await page.evaluate(() => [...document.querySelectorAll('.thumbnail')]
            .filter(el => el.querySelector('span') && el.getBoundingClientRect().width < 40).length);
        assert.equal(overfull, 0, `${overfull} cells are too narrow for the timecode they carry`);
        console.log(`PASS the lane draws ${Object.keys(drawn).length} rung(s) at once (${JSON.stringify(drawn)})`);

        // --- a frame fills the box it is drawn in -----------------------------
        //
        // The lane crops with `cover`, so "does the picture reach every edge" is a question
        // about *painted pixels*, not about boxes: a stray `object-position` offset slides
        // the frame inside an image box that still measures exactly the cell, and no
        // geometry check can see it. So each probed cell is shot twice with a different
        // background painted behind it — whatever changed colour is background the frame
        // did not cover. (The override stays in place for the rest of the run; the checks
        // after this one look at layout, never at pixels.)
        await page.addStyleTag({ content: '.thumbnail { background: var(--probe-bg, #17202b) !important; }' });
        const probeBackground = color => page.evaluate(c => document.documentElement.style.setProperty('--probe-bg', c), color);
        // The frame must also *cover* the cell with pixels of its own rather than by
        // upscaling: a frame cut for a shorter lane is what leaves the strip soft.
        const probeFill = async (label) => {
            await page.waitForFunction(
                () => [...document.querySelectorAll('.thumbnail img')].some(i => i.complete && i.naturalWidth > 0),
                null, { timeout: 60000 }
            );
            const boxes = await page.evaluate(() => [...document.querySelectorAll('.thumbnail img')]
                .filter(img => img.complete && img.naturalWidth > 0)
                .map(img => {
                    const r = img.getBoundingClientRect();
                    return {
                        x: r.x, y: r.y, width: r.width, height: r.height,
                        natW: img.naturalWidth, natH: img.naturalHeight, src: img.getAttribute('src')
                    };
                })
                // Whole cells that are actually on screen: a half-scrolled cell would be
                // measured against a clipped screenshot.
                .filter(b => b.width > 12 && b.height > 12 && b.x >= 0 && b.y >= 0
                    && b.x + b.width <= innerWidth && b.y + b.height <= innerHeight));
            assert(boxes.length > 0, `${label}: nothing drawn to probe`);
            // Sampled across the lane rather than from one end: the lane label and the
            // playhead sit over the leftmost cells.
            const picks = [...new Set([0, Math.floor(boxes.length / 2), boxes.length - 1].map(i => boxes[i]))];
            for (const b of picks) {
                assert(b.natW >= b.width, `${label}: a frame is ${b.natW}px wide for a ${Math.round(b.width)}px cell — cover would upscale it sideways`);
                assert(b.natH >= b.height, `${label}: a frame is ${b.natH}px tall for a ${Math.round(b.height)}px cell — cover would upscale it lengthways`);
            }
            const probed = Math.min(5, boxes.length);
            for (let i = 0; i < probed; i++) {
                const b = boxes[Math.floor(i * (boxes.length - 1) / Math.max(1, probed - 1))];
                // Inset by a pixel: the cell's own right border and a fractional box edge
                // are blends, and a blend is not a hole.
                const clip = {
                    x: b.x + 1, y: b.y + 1,
                    width: Math.max(1, b.width - 2), height: Math.max(1, b.height - 2)
                };
                // A frame landing between the two shots would show up as a difference that
                // is not a hole, so the lane's frames are checked around each attempt and
                // the probe simply starts over if one arrived.
                const frameSources = () => page.evaluate(
                    () => [...document.querySelectorAll('.thumbnail img')].map(i => i.getAttribute('src')).join('|'));
                for (let attempt = 0; ; attempt++) {
                    const before = await frameSources();
                    await probeBackground('#ff00ff');
                    const lit = await page.screenshot({ clip });
                    await probeBackground('#001a33');
                    const dark = await page.screenshot({ clip });
                    if (await frameSources() !== before) {
                        assert(attempt < 4, `${label}: the lane kept swapping frames while it was being probed`);
                        continue;
                    }
                    const leak = paintedLeak(lit, dark);
                    assert.equal(leak, 0,
                        `${label}: ${leak}px of a ${Math.round(b.width)}x${Math.round(b.height)} cell are background, not frame`);
                    break;
                }
            }
            return picks[0];
        };
        const filled = await probeFill('at the default lane height');
        console.log(`PASS every probed cell is filled edge to edge (${filled.natW}x${filled.natH} frames in ${Math.round(filled.width)}x${Math.round(filled.height)} cells)`);

        // Dragging the lane taller crosses a rung of the width ladder, and the frames have
        // to be cut again for the new height — otherwise the strip is upscaled for the rest
        // of the session, which is exactly what a lane height baked in at load time did.
        const lanePx = await page.evaluate(() => editor.laneHeights.thumbnails);
        const rungBefore = await page.evaluate(() => Math.max(0, ...editor.frameDebug().thumbs.map(f => f.w || 0)));
        await page.evaluate(() => { editor.laneHeights.thumbnails = 200; });
        await page.waitForFunction(w => editor.frameDebug().thumbs.some(f => (f.w || 0) > w), rungBefore, { timeout: 60000 })
            .catch(async () => { throw new Error(`the lane never re-cut its frames for a 200px lane (rung stayed ${JSON.stringify(await page.evaluate(() => editor.frameDebug().thumbs.map(f => f.w)))}`) });
        const taller = await probeFill('after dragging the lane to 200px');
        assert(taller.natH >= 200, `a 200px lane must be served frames at least that tall, got ${taller.natH}`);
        console.log(`PASS a taller lane re-cut its frames ${rungBefore}px -> ${taller.natW}px wide and stayed filled`);
        await page.evaluate(px => { editor.laneHeights.thumbnails = px; }, lanePx);
        await page.waitForTimeout(800);

        // Nothing may ever be drawn finer than the ladder: a level from an earlier zoom
        // adopted as a layer is what paves the strip into an unreadable barcode.
        await page.evaluate(() => editor.setZoom(64));
        await waitFor(() => editor.thumbnailLevel(editor.thumbnailTarget) > 0, 'the deeper target level never filled');
        const deep = await describe();
        assert(deep.rungs.includes(deep.target), `at depth the target must still be drawn, got ${JSON.stringify(deep)}`);
        assert(deep.rungs[0] >= deep.target, `a rung finer than the target was drawn: ${JSON.stringify(deep.rungs)}`);
        for (const px of deep.widths) assert(px >= 6, `a cell is ${px}px wide, which is a sliver`);
        await page.evaluate(() => editor.zoomFit());
        await page.waitForTimeout(800);

        // --- zooming in refines further ---------------------------------------
        // The lane's job is to keep subdividing until its cells are thumb-sized: a level
        // number counts *down* towards the frame cache's own 0.1 s grid, so a deeper zoom
        // has to reach a lower level than the one it started at.
        await page.evaluate(() => editor.setZoom(16));
        const finerTarget = await page.waitForFunction(
            level => editor.thumbnailTarget < level && editor.thumbnailLevel(editor.thumbnailTarget) > 0,
            target, { timeout: 60000 }
        ).then(() => page.evaluate(() => editor.thumbnailTarget)).catch(async () => {
            throw new Error(`zooming in did not refine below level ${target}: ${JSON.stringify(await page.evaluate(() => [editor.zoom, editor.thumbnailTarget, editor.thumbnailProgress.map(p => p.join('/'))]))}`);
        });
        const finerWidths = await page.evaluate(() => {
            const seen = new Set()
            for (const el of document.querySelectorAll('.thumbnail')) seen.add(Math.round(el.getBoundingClientRect().width))
            return [...seen].sort((a, b) => a - b)
        });
        assert(finerTarget <= target - 3, `a 16x zoom should be several levels finer than ${target}, got ${finerTarget}`);
        // Cells shrink as the zoom deepens until the frame cache's own 0.1 s grid runs
        // out, and they must stay legible until then: a cell thinner than its frame is a
        // sliver nobody asked for.
        assert(finerWidths[finerWidths.length - 1] >= 7, `zoomed in, cells must stay legible, got ${JSON.stringify(finerWidths)}`);
        console.log(`PASS zooming in refined from level ${target} to ${finerTarget} with cells up to ${finerWidths[finerWidths.length - 1]}px`);
        await page.evaluate(() => editor.zoomFit());
        await page.waitForTimeout(600);

        // --- the hover popup lives on the thumbnail lane only -----------------
        const lane = await page.locator('[data-lane="thumbnails"]').boundingBox();
        const ruler = await page.locator('[data-lane="ruler"]').boundingBox();
        const clips = await page.locator('[data-lane="clips"]').boundingBox();
        assert(!await page.locator('.hover-popup').count(), 'no popup before the pointer is on the lane');
        await page.mouse.move(ruler.x + ruler.width * 0.4, ruler.y + ruler.height / 2);
        await page.waitForTimeout(150);
        assert.equal(await page.locator('.hover-popup').count(), 0, 'the ruler lane must not open the preview');
        await page.mouse.move(clips.x + clips.width * 0.4, clips.y + clips.height / 2);
        await page.waitForTimeout(150);
        assert.equal(await page.locator('.hover-popup').count(), 0, 'the clip lane must not open the preview');
        console.log('PASS the preview popup appears only over the thumbnail lane');

        // --- the popup floats above the timeline, not on it --------------------
        // The point of the preview is to show what is *there*; covering the lane or the
        // ruler to do it hides the context it is meant to be read against.
        const place = async (x, y) => {
            await page.evaluate(ax => { window.__aimX = ax }, x)
            await page.mouse.move(x, y)
            await page.waitForTimeout(500)
            return page.evaluate(() => {
                const pop = document.querySelector('.hover-popup')
                const lane = document.querySelector('[data-lane="thumbnails"]')
                const ruler = document.querySelector('[data-lane="ruler"]')
                const mark = document.querySelector('.hover-cursor')
                if (!pop || !lane) return null
                const p = pop.getBoundingClientRect(), l = lane.getBoundingClientRect()
                return {
                    lane: { top: l.top, bottom: l.bottom },
                    ruler: { top: ruler.getBoundingClientRect().top },
                    pop: { top: p.top, bottom: p.bottom, left: p.left, right: p.right, w: p.width, h: p.height },
                    mark: mark ? { clientX: mark.getBoundingClientRect().left, style: mark.style.left } : null,
                    viewport: document.querySelector('[data-panel="timeline"]').scrollLeft,
                    view: { w: innerWidth, h: innerHeight },
                    time: window.editor.hoverTimecode,
                    aim: window.__aimX,
                }
            })
        }
        const floating = await place(lane.x + lane.width * 0.4, lane.y + lane.height / 2);
        assert(floating, 'no popup to measure');
        // Either it floats clear above the lane, or it tucks in below it: never across it,
        // and never over the ruler.
        const clearance = Math.min(Math.abs(floating.lane.top - floating.pop.bottom), Math.abs(floating.pop.top - floating.lane.bottom))
        assert(floating.pop.bottom <= floating.lane.top + 1 || floating.pop.top >= floating.lane.bottom - 1,
            `the popup must sit clear of the lane, got ${JSON.stringify(floating.pop)} vs lane ${JSON.stringify(floating.lane)}`);
        // Floating above the timeline is the ideal — clear even of the ruler. Tucking under
        // the lane is the fallback when the window has no room up there, and it still keeps
        // the ruler visible, which is the one thing a preview must not hide.
        assert(floating.pop.bottom <= floating.ruler.top + 1 || floating.pop.top >= floating.lane.bottom - 1,
            `the popup covers the ruler: ${JSON.stringify(floating)}`);
        assert(floating.pop.top >= -1 && floating.pop.bottom <= floating.view.h + 1,
            `the popup must stay inside the window, got top ${floating.pop.top} bottom ${floating.pop.bottom} of ${floating.view.h}`);
        // A preview, not a second player.
        assert(floating.pop.h >= 56 && floating.pop.h <= 240, `popup height out of range: ${floating.pop.h}`);
        // Both overlays follow the pointer, not the timeline, at every zoom. The popup is
        // fixed-positioned while the ruler mark sits inside the scrolled content, so the
        // invariant that covers both coordinate systems is that they share a centre — and
        // that the centre is under the cursor.
        for (const zoom of [1, 12, 60, 240]) {
            await page.evaluate(z => editor.setZoom(z), zoom)
            await page.waitForTimeout(1200)
            // The lane is wider than the window at depth, so aim inside the viewport.
            const panel = await page.locator('[data-panel="timeline"]').boundingBox()
            const laneBox = await page.locator('[data-lane="thumbnails"]').boundingBox()
            const here = await place(panel.x + panel.width * 0.55, laneBox.y + laneBox.height / 2)
            assert(here, `no popup at zoom ${zoom}`)
            assert(here.mark, `no ruler mark at zoom ${zoom}`)
            // Anchored to the cursor, not merely near it: the popup's frame grid is coarse
            // on purpose (one step can be hundreds of pixels at depth), and hanging the
            // overlay off the frame instead of the pointer is what made it drift sideways.
            // The residual is the horizontal margin clamp at the window edges, plus a pixel
            // of border — nothing that scales with the zoom.
            const off = Math.abs((here.pop.left + here.pop.right) / 2 - here.aim)
            assert(off <= 10, `at zoom ${zoom} the popup centre is ${Math.round(off)}px from the cursor (popup is ${Math.round(here.pop.w)}px wide)`);
            const gap = Math.abs(here.mark.clientX - here.aim)
            assert(gap <= 10, `at zoom ${zoom} the ruler mark is ${Math.round(gap)}px from the cursor (t=${here.time}, mark ${here.mark.style}, scroll ${here.viewport})`);
        }
        console.log(`PASS the popup floats clear of the lane (${Math.round(floating.pop.h)}px) and both it and its ruler mark track the cursor at every zoom`);

        // --- a live pointer never blocks, and never stacks up requests --------
        //
        // Frame requests are counted rather than probed: the point is that a sweeping
        // pointer asks once per cell it crosses, not once per mousemove. Fixed at a
        // middling zoom first, so "how many positions did it cross" is a property of the
        // sweep rather than of wherever the previous check left the timeline.
        // `setZoom` parks the viewport where it already was, which at the zoom the previous
        // check finished on can be past the end of the recording — where every pointer
        // position clamps to the same instant and the sweep has nothing to cross.
        await page.evaluate(() => editor.zoomFit());
        await page.waitForTimeout(500);
        await page.evaluate(() => editor.setZoom(12));
        await page.waitForTimeout(1500);
        const sweepLane = await page.locator('[data-lane="thumbnails"]').boundingBox();
        const sweepPanel = await page.locator('[data-panel="timeline"]').boundingBox();
        // Observed, not intercepted: holding every frame response open would back the
        // page up behind its own connection limit and stall the pointer events this check
        // is measuring.
        const requestedHover = [];
        const noteHover = request => {
            const url = new URL(request.url())
            if (url.pathname.endsWith('/frame') && url.searchParams.get('p') === '1') requestedHover.push(url.pathname + url.search)
        }
        page.on('request', noteHover);
        const sweepY = sweepLane.y + sweepLane.height / 2;
        await page.mouse.move(sweepPanel.x + sweepPanel.width * 0.1, sweepY);
        await page.waitForFunction(() => document.querySelector('.hover-popup'), null, { timeout: 5000 });
        // Drag the pointer across the timeline in small steps, sampling as it goes.
        const samples = [];
        for (let i = 0; i < 20; i++) {
            const x = sweepPanel.x + sweepPanel.width * (0.1 + 0.04 * i);
            await page.mouse.move(x, sweepY);
            const sample = await page.evaluate(() => {
                const img = document.querySelector('.hover-popup img');
                return { tc: editor.hoverTimecode, src: img ? img.getAttribute('src') : null, open: editor.hoverPreviewOpen };
            });
            samples.push(sample);
            assert(sample.open, 'the popup must stay open while the pointer is on the lane');
            await page.waitForTimeout(40);
        }
        const distinct = new Set(samples.map(s => s.tc));
        assert(distinct.size >= 10, `the preview should follow the cursor, only ${distinct.size} distinct positions seen`);
        // Motion must be monotone with the pointer, not stuck on an early frame.
        for (let i = 1; i < samples.length; i++) {
            assert(samples[i].tc >= samples[i - 1].tc, 'the preview timecode went backwards while moving right');
        }
        // Every cell the cursor crossed is asked for exactly once, and the pool ends up
        // holding them: that is what makes moving back over the same ground instant.
        const distinctRequests = new Set(requestedHover);
        assert.equal(distinctRequests.size, requestedHover.length, `the hover lane re-requested a cell: ${requestedHover.length} requests for ${distinctRequests.size} cells`);
        const hoverPool = await page.evaluate(() => editor.frameDebug().hover.length);
        assert(hoverPool >= 3, `moving across cells should pool their frames, got ${hoverPool}`);
        console.log(`PASS a sweeping pointer asked for ${distinctRequests.size} cells once each and pooled ${hoverPool}`);
        page.off('request', noteHover);

        // The frame the cursor lands on must be on screen without waiting for a decode:
        // after the sweep above, every cell it crossed is in the pool.
        const revisit = { x: sweepPanel.x + sweepPanel.width * 0.1, y: sweepY };
        await page.mouse.move(revisit.x, revisit.y);
        await page.waitForTimeout(60);
        const instant = await page.evaluate(() => {
            const img = document.querySelector('.hover-popup img');
            return { src: img ? img.getAttribute('src') : null, known: editor.frameDebug() }
        });
        assert(instant.src, 'a position with cached frames must paint immediately');
        const all = [...instant.known.hover, ...instant.known.thumbs]
        assert(all.some(f => f.url === instant.src), 'the popup painted a frame that is not in either pool');
        console.log(`PASS a revisited position painted from the pool immediately (${all.length} frames held)`);

        // --- leaving the lane closes it ---------------------------------------
        await page.mouse.move(clips.x + clips.width * 0.5, clips.y + clips.height / 2);
        await page.waitForFunction(() => !document.querySelector('.hover-popup'), null, { timeout: 3000 });
        console.log('PASS the popup closes when the pointer leaves the lane');

        // --- unsaved data blocks the exit -------------------------------------
        //
        // The draft is written on a debounce, so the guard only has anything to warn
        // about inside that window. Stretching the debounce is what makes this test
        // deterministic instead of a race against a 500 ms timer; it does not change
        // what the guard does.
        await page.evaluate(() => { editor.persistDelay.set(60000); });
        const dialogTypes = [];
        page.on('dialog', async d => { dialogTypes.push(d.type()); await d.accept(); });
        await page.evaluate(() => { editor.currentTime = 30; });
        await page.keyboard.press('i');
        await page.waitForFunction(() => editor.segments.length > 0);
        assert.equal(await page.evaluate(() => editor.edited), true, 'an edit must mark the draft unsaved');
        await page.reload({ waitUntil: 'domcontentloaded' });
        assert(dialogTypes.includes('beforeunload'), `an edit must raise the browser leave-site prompt on reload, saw ${JSON.stringify(dialogTypes)}`);
        console.log('PASS an unsaved edit raises the exit prompt');

        // The prompt accepted. The draft itself is written by the flush on `pagehide`,
        // which the browser runs on the way out.
        await expose();
        await page.waitForFunction(() => editor.detail && !editor.editorLoading);
        assert.equal(await page.evaluate(() => editor.edited), false, 'arriving back with a saved draft has nothing to warn about');
        const restored = await page.evaluate(() => JSON.parse(localStorage.getItem('xhcut:editor:v1:' + editor.selected.id))?.segments?.length || 0);
        assert(restored > 0, 'the draft the prompt warned about must have been written on the way out');
        console.log('PASS the outgoing flush saved the draft');

        // ...and a page with nothing pending reloads without a prompt.
        dialogTypes.length = 0;
        await page.evaluate(() => { editor.persistDelay.set(60000); });
        await page.reload({ waitUntil: 'domcontentloaded' });
        await page.waitForTimeout(1500);
        assert.deepEqual(dialogTypes, [], 'a page with no pending edits must not prompt');
        console.log('PASS no prompt once the draft is saved');

        assert.deepEqual(errors, []);
        console.log('All timeline preview checks passed.');
    } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
