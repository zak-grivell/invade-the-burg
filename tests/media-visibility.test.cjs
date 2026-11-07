const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../src/main/resources/browser/media-visibility.js'), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));

function fixture(instagram = true, code = script) {
    let clock = 0, id = 0;
    const timers = new Map(), videos = [], listeners = {}, windowListeners = {}, messages = [];
    const emit = (name, target) => (listeners[name] || []).forEach(fn => fn({target}));
    class Media {
        constructor(playing = false) {
            Object.assign(this, {tagName: 'VIDEO', paused: !playing, ended: false, isConnected: true,
                plays: 0, pauses: 0, muted: false, soundBlocked: false, blocked: false, readyState: 4,
                src: 'video.mp4', deferred: null, failures: [], rect: {top: 0, left: 0, right: 640, bottom: 480}});
            videos.push(this);
        }
        getBoundingClientRect() { return this.rect; }
        pause() { this.pauses++; const changed = !this.paused; this.paused = true; if (changed) emit('pause', this); }
        play() {
            this.plays++;
            const failure = this.failures.shift() || ((this.blocked || this.soundBlocked && !this.muted) ? 'NotAllowedError' : null);
            if (failure) return Promise.reject(Object.assign(new Error(failure), {name: failure}));
            this.paused = false; emit('play', this);
            const promise = this.deferred || Promise.resolve();
            return promise.then(() => { if (!this.paused) emit('playing', this); });
        }
    }
    const window = {location: {hostname: instagram ? 'www.instagram.com' : 'example.com'}, frames: [], innerWidth: 1000, innerHeight: 800,
        getComputedStyle: () => ({display: 'block', visibility: 'visible', opacity: '1'}),
        addEventListener(name, fn) { windowListeners[name] = fn; }};
    window.parent = window;
    const document = {
        addEventListener(name, fn) { (listeners[name] ||= []).push(fn); }, querySelectorAll: () => videos,
        documentElement: {appendChild(button) { button.isConnected = true; }},
        createElement: () => ({style: {}, setAttribute() {}, addEventListener(name, fn) { this[name] = fn; }, remove() { this.isConnected = false; }})
    };
    const context = vm.createContext({window, document, HTMLMediaElement: Media, Date: {now: () => clock},
        setTimeout(fn, ms) { timers.set(++id, {fn, at: clock + ms}); return id; }, clearTimeout(id) { timers.delete(id); }});
    return {window, emit, messages, windowListeners, media: playing => new Media(playing),
        state: () => window.__reelBreakMediaState,
        visibility: hidden => vm.runInContext(code.replace('/*HIDDEN*/', String(hidden)), context),
        advance(ms) { clock += ms; for (const [id, t] of Array.from(timers)) if (t.at <= clock) { timers.delete(id); t.fn(); } }};
}

(async function () {
    {
        const f = fixture(), outgoing = f.media(true), incoming = f.media(false);
        incoming.rect = {top: 600, left: 0, right: 640, bottom: 1080};
        f.visibility(false);
        await incoming.play();
        assert.equal(incoming.paused, false, 'incoming Reel may start before it occupies most of the viewport');
        assert.equal(incoming.pauses, 0, 'plugin must never interrupt site playback during scroll-snap');
        outgoing.pause();
        incoming.rect.top = 0; incoming.rect.bottom = 480;
        f.advance(10000); await tick();
        assert.equal(incoming.paused, false);
        assert.equal(outgoing.plays, 0, 'plugin does not restart the Reel Instagram paused');
    }
    {
        const f = fixture(), video = f.media(false); video.soundBlocked = true;
        f.visibility(false); await video.play();
        assert.equal(video.plays, 2); assert.equal(video.muted, true); assert.equal(video.paused, false);
        assert.equal(video.pauses, 0, 'policy fallback does not pause/restart the player');
        assert.equal(f.state().attempting.size, 0);
    }
    {
        const f = fixture(false), video = f.media(false); video.soundBlocked = true;
        f.visibility(false);
        await assert.rejects(video.play(), {name: 'NotAllowedError'});
        assert.equal(video.plays, 1); assert.equal(video.muted, false, 'unrelated websites keep their own policy');
        assert.equal(f.state().failures[0].name, 'NotAllowedError');
    }
    {
        const f = fixture(), video = f.media(false); video.blocked = true;
        f.visibility(false);
        await assert.rejects(video.play(), {name: 'NotAllowedError'});
        assert.equal(video.plays, 2, 'only one muted fallback is attempted');
        f.advance(60000); await tick(); assert.equal(video.plays, 2, 'no speculative autoplay loop');
        assert.equal(f.state().retryButton.isConnected, true);
        video.blocked = false; f.state().retryButton.click(); await tick();
        assert.equal(video.paused, false); assert.equal(f.state().retryButton, null);
    }
    {
        const f = fixture(), playing = f.media(true), manuallyPaused = f.media(false);
        f.visibility(true); assert.equal(playing.paused, true);
        f.visibility(false); await tick();
        assert.equal(playing.paused, false); assert.equal(manuallyPaused.plays, 0);
        playing.pause(); f.advance(60000); await tick();
        assert.equal(playing.paused, true, 'manual pauses are authoritative without timing guesses');
        for (let i = 0; i < 20; i++) f.visibility(false);
        assert.equal(playing.plays, 1, 'lifecycle notifications cannot restart a manually paused Reel');
    }
    {
        const f = fixture(), video = f.media(true);
        f.visibility(true); video.pause(); f.visibility(false); await tick();
        assert.equal(video.plays, 0, 'a site pause while hidden cancels retained playback');
    }
    {
        const f = fixture(), video = f.media(true);
        f.visibility(true); video.src = 'reused-element.mp4'; f.visibility(false); await tick();
        assert.equal(video.plays, 0, 'retained resume cannot play a different Reel in a reused element');
        await video.play(); assert.equal(video.plays, 1, 'new source follows the site’s explicit play request');
    }
    {
        const f = fixture(), video = f.media(false);
        f.visibility(false);
        let reject;
        video.deferred = new Promise((_, fail) => { reject = fail; });
        const result = video.play(); video.pause();
        reject(Object.assign(new Error('Site cancelled'), {name: 'AbortError'}));
        await assert.rejects(result, {name: 'AbortError'});
        assert.equal(f.state().failures.length, 0, 'intentional cancellation is not reported or retried');
        f.advance(10000); await tick(); assert.equal(video.plays, 1);
    }
    {
        const f = fixture(), video = f.media(false); f.visibility(false);
        let resolve;
        video.deferred = new Promise(done => { resolve = done; });
        const result = video.play(); f.visibility(true); resolve(); await result;
        assert.equal(video.paused, true, 'late completion cannot resume hidden media');
        video.deferred = null; f.visibility(false); await tick(); assert.equal(video.paused, false);
    }
    {
        const f = fixture(), video = f.media(false); f.visibility(false);
        let resolve;
        video.deferred = new Promise(done => { resolve = done; });
        const result = video.play(); f.advance(10000); await tick();
        assert.equal(video.pauses, 0, 'slow video loading is never aborted by a watchdog');
        video.deferred = null; f.window.__reelBreakRetryMedia(true); await tick();
        resolve(); await result;
        assert.equal(video.paused, false, 'pending native playback does not lock retry');
    }
    {
        const f = fixture(), front = f.media(true), back = f.media(true), removed = f.media(true);
        back.rect.top = 900; back.rect.bottom = 1380;
        f.visibility(true); removed.isConnected = false;
        f.visibility(false); await tick();
        assert.equal(front.paused, false); assert.equal(back.paused, true); assert.equal(removed.plays, 0);
    }
    {
        const f = fixture(), video = f.media(true);
        f.window.frames.push({postMessage(data) { f.messages.push(data); }});
        f.visibility(true); assert.equal(f.messages.at(-1).hidden, true);
        f.windowListeners.message({source: {}, data: {reelBreakVisibility: true, hidden: false}});
        assert.equal(f.state().hidden, true);
        f.windowListeners.message({source: f.window.parent, data: {reelBreakVisibility: true, hidden: false}});
        await tick(); assert.equal(video.paused, false);
    }
    {
        const f = fixture(), video = f.media(false); f.visibility(false);
        video.failures = ['AbortError'];
        await assert.rejects(video.play(), {name: 'AbortError'});
        f.advance(20000); await tick(); assert.equal(video.plays, 1, 'website cancellations retain native promise semantics');
        assert.equal(f.state().failures[0].name, 'AbortError');
        await video.play(); assert.equal(video.paused, false, 'site can start its replacement request immediately');
    }
    assert.equal(script.includes('setInterval'), false);
    assert.equal(script.includes("addEventListener('scroll'"), false, 'scrolling never controls playback');
    console.log('Passed 13 player-ownership scenarios: scroll-snap handoff, policy fallback, cancellation, reused elements, hidden races, manual pause, and native promise semantics.');
})().catch(error => { console.error(error); process.exitCode = 1; });
