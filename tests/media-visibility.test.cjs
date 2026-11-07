const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../src/main/resources/browser/media-visibility.js'), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));

function fixture(instagram = false) {
    let clock = 0, timerId = 0; const timers = new Map();
    const later = (fn, ms = 0) => { const id = ++timerId; timers.set(id, {fn, at: clock + ms}); return id; };
    const advance = ms => { clock += ms; for (const [id, timer] of Array.from(timers)) if (timer.at <= clock) { timers.delete(id); timer.fn(); } };
    const listeners = {}, windowListeners = {}, videos = [], messages = [];
    const document = {
        addEventListener(name, fn) { (listeners[name] ||= []).push(fn); },
        querySelectorAll() { return videos; },
        createElement() {
            return { style: {}, isConnected: false, setAttribute() {},
                addEventListener(name, fn) { this[name] = fn; },
                remove() { this.isConnected = false; } };
        },
        documentElement: { appendChild(button) { button.isConnected = true; } }
    };
    const window = { location: {hostname: instagram ? 'www.instagram.com' : 'example.com', pathname: '/reels/'}, getComputedStyle() { return {display: 'block', visibility: 'visible', opacity: '1'}; }, frames: [], innerWidth: 1000, innerHeight: 800,
        addEventListener(name, fn) { windowListeners[name] = fn; } };
    window.parent = window;
    const context = vm.createContext({window, document, setTimeout: later, clearTimeout: id => timers.delete(id), Date: {now: () => clock}, requestAnimationFrame: fn => later(fn)});
    const emit = (name, target, extra = {}) => (listeners[name] || []).forEach(fn => fn({target, ...extra}));
    const media = (playing) => {
        const item = { tagName: 'VIDEO', paused: !playing, ended: false, isConnected: true,
            readyState: 4, closest() { return this; }, failures: [], plays: 0, blocked: false, soundBlocked: false, muted: false, src: "video.mp4", autoplay: false, deferred: null,
            rect: {top: 0, left: 0, right: 640, bottom: 480, width: 640, height: 480},
            getBoundingClientRect() { return this.rect; },
            pause() { const changed = !this.paused; this.paused = true; if (changed) emit('pause', this); },
            play() {
                this.plays++;
                if (this.failures.length) return Promise.reject(Object.assign(new Error('Interrupted'), {name: this.failures.shift()}));
                if (this.blocked || (this.soundBlocked && !this.muted)) return Promise.reject(Object.assign(new Error('Gesture required'), {name: 'NotAllowedError'}));
                this.paused = false; emit('play', this);
                return this.deferred || Promise.resolve();
            }
        };
        videos.push(item);
        return item;
    };
    return {window, media, emit, messages, windowListeners, advance,
        async settle() { for (const ms of [180, 0, 200]) { advance(ms); await tick(); } },
        state: () => window.__reelBreakMediaState,
        visibility: hidden => vm.runInContext(script.replace('/*HIDDEN*/', String(hidden)), context)};
}

(async function () {
    {
        const f = fixture(), playing = f.media(true), manual = f.media(false);
        f.visibility(true); assert.equal(playing.paused, true);
        f.visibility(false); await tick();
        assert.equal(playing.paused, false); assert.equal(playing.plays, 1);
        assert.equal(manual.plays, 0);
        f.visibility(false); await tick(); assert.equal(playing.plays, 1);
    }
    {
        const f = fixture(), video = f.media(true);
        f.visibility(true); video.blocked = true; f.visibility(false); await tick();
        assert.equal(f.state().paused.has(video), true, 'rejected media remains queued');
        assert.equal(f.state().lastError, 'NotAllowedError');
        assert.equal(f.state().retryButton.isConnected, true, 'a real click is available');
        for (let i = 0; i < 240; i++) f.visibility(false);
        assert.equal(video.plays, 1, 'does not spin on rejected autoplay');
        video.blocked = false; f.state().retryButton.click(); await tick();
        assert.equal(video.paused, false); assert.equal(f.state().paused.size, 0);
        assert.equal(f.state().retryButton, null);
    }
    {
        const f = fixture(), video = f.media(true);
        f.visibility(true);
        let resolve;
        video.deferred = new Promise(done => { resolve = done; });
        f.visibility(false); f.visibility(true); resolve(); await tick();
        assert.equal(video.paused, true, 'late play completion cannot resume hidden media');
        assert.equal(f.state().paused.has(video), true);
        video.deferred = null; f.visibility(false); await tick(); assert.equal(video.paused, false);
    }
    {
        const f = fixture(), removed = f.media(true), ended = f.media(true);
        f.visibility(true); removed.isConnected = false; ended.ended = true;
        f.visibility(false); await tick();
        assert.equal(removed.plays, 0); assert.equal(ended.plays, 0);
        assert.equal(f.state().paused.size, 0);
    }
    {
        const f = fixture(); f.visibility(true);
        const video = f.media(false); video.play(); await tick();
        assert.equal(video.paused, true, 'site autoplay remains paused while hidden');
        f.visibility(false); await tick(); assert.equal(video.paused, false);
        video.rect.top = 900; video.rect.bottom = 1380;
        const next = f.media(false); next.autoplay = true; f.emit('loadeddata', next); await f.settle();
        assert.equal(next.paused, false, 'a new autoplay video can start without Java polling');
    }
    {
        const f = fixture(), video = f.media(true);
        f.window.frames.push({postMessage(data) { f.messages.push(data); }});
        f.visibility(true);
        assert.equal(f.messages.at(-1).hidden, true);
        f.windowListeners.message({source: {}, data: {reelBreakVisibility: true, hidden: false}});
        assert.equal(f.state().hidden, true, 'unrelated window messages are ignored');
        f.windowListeners.message({source: f.window.parent, data: {reelBreakVisibility: true, hidden: false}});
        await tick(); assert.equal(video.paused, false);
    }
    {
        const f = fixture(true), current = f.media(false), background = f.media(false);
        current.soundBlocked = true;
        background.autoplay = true; background.rect.top = 900; background.rect.bottom = 1380;
        f.visibility(false); await f.settle();
        assert.equal(current.paused, false, 'current Reel starts without a click');
        assert.equal(current.muted, true, 'sound rejection falls back to muted autoplay');
        assert.equal(current.plays, 2);
        assert.equal(background.plays, 0, 'preloaded background Reel never starts');
        current.rect.top = -900; current.rect.bottom = -420;
        background.rect.top = 0; background.rect.bottom = 480;
        f.emit('scroll', null); await f.settle();
        assert.equal(current.paused, true);
        assert.equal(background.paused, false, 'scrolling to the next Reel starts it');
        f.emit('click', background, {isTrusted: true}); background.pause();
        f.emit('canplay', background); await f.settle();
        assert.equal(background.paused, true, 'same Reel is not restarted after a manual pause');
        background.src = 'next-source.mp4';
        f.emit('loadeddata', background); await f.settle();
        assert.equal(background.paused, false, 'reused video element starts its new source');
    }
    {
        const f = fixture(), video = f.media(true);
        f.visibility(true); video.deferred = new Promise(() => {});
        f.visibility(false); f.advance(8000); await tick();
        assert.equal(f.state().attempting.size, 0, 'a stalled promise releases its retry lock');
        assert.equal(f.state().lastError, 'PlaybackTimeout');
        video.deferred = null; f.state().retryButton.click(); await tick();
        assert.equal(video.paused, false, 'retry succeeds after timeout');
    }
    {
        const f = fixture(), front = f.media(true), back = f.media(true);
        back.rect.top = 900; back.rect.bottom = 1380;
        f.visibility(true); f.visibility(false); await tick();
        assert.equal(front.paused, false);
        assert.equal(back.paused, true, 'offscreen queued media does not resume');
    }
    {
        const f = fixture(true), video = f.media(false);
        let completeOld;
        video.deferred = new Promise(resolve => { completeOld = resolve; });
        f.visibility(false); await f.settle();
        assert.equal(f.state().attempting.size, 1);
        video.src = 'replacement.mp4'; video.deferred = null;
        f.emit('loadedmetadata', video); await f.settle();
        assert.equal(video.plays, 2, 'a changed source cancels the old pending attempt');
        assert.equal(f.state().attempting.size, 0);
        completeOld(); await tick();
        assert.equal(video.paused, false, 'obsolete promise cannot change current playback state');
    }
    {
        const f = fixture(true), video = f.media(false);
        video.failures = ['AbortError', 'AbortError'];
        f.visibility(false); await f.settle();
        assert.equal(video.plays, 1);
        f.advance(300); await tick(); assert.equal(video.plays, 2);
        f.advance(700); await tick(); assert.equal(video.plays, 3);
        assert.equal(video.paused, false, 'interrupted playback recovers without a click');
        assert.equal(f.state().failures.length, 2);
        video.pause(); f.advance(1400); await tick();
        assert.equal(video.paused, false, 'a site pause just after playback recovers');
    }
    {
        const f = fixture(true), video = f.media(false);
        video.failures = Array(20).fill('AbortError');
        f.visibility(false); await f.settle();
        for (let i = 0; i < 10; i++) { f.advance(2000); await tick(); }
        assert.equal(video.plays, 4, 'automatic retries are bounded');
        for (let i = 0; i < 10; i++) { f.emit('canplay', video); await f.settle(); }
        assert.equal(video.plays, 4, 'readiness events do not reset the retry budget');
    }
    {
        const f = fixture(true), video = f.media(false);
        video.readyState = 1;
        f.visibility(false); await f.settle(); assert.equal(video.plays, 0, 'waits for video data');
        video.readyState = 4; f.emit('canplay', video); await f.settle(); assert.equal(video.plays, 1);
        f.emit('click', video, {isTrusted: true}); video.pause();
        f.emit('canplay', video); await f.settle(); f.advance(5000); await tick();
        assert.equal(video.paused, true, 'manual pause survives subsequent readiness events');
    }
    {
        const f = fixture(true), video = f.media(false);
        f.visibility(false); f.advance(0);
        for (let i = 0; i < 6; i++) { f.emit('scroll', null); f.advance(100); await tick(); }
        assert.equal(video.plays, 0, 'does not start midway through scrolling');
        await f.settle(); assert.equal(video.plays, 1);
        video.failures = ['AbortError']; video.pause();
        f.visibility(true); f.advance(5000); await tick();
        assert.equal(video.plays, 1, 'hiding cancels scheduled retries');
    }
    assert.equal(script.includes('setInterval'), false);
    console.log('Passed 14 media lifecycle scenarios, including automatic Reel switching, muted autoplay fallback, background exclusion, source reuse, manual pause, and stalled-promise recovery.');
})().catch(error => { console.error(error); process.exitCode = 1; });
