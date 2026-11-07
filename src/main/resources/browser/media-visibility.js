(function (hidden) {
    const key = '__reelBreakMediaState';
    let state = window[key];
    if (!state) {
        state = window[key] = {
            hidden: false, paused: new Set(), attempting: new Map(), lastError: null,
            resumeAttempts: 0, retryButton: null, failures: []
        };
        const automatic = new WeakMap();
        let scrollTimer = null, scrolling = false, userIntent = null;
        const sourceOf = media => media.src || media.currentSrc || '';
        const recordFor = function (media) {
            let record = automatic.get(media);
            if (!record || record.source !== sourceOf(media)) {
                if (record) clearTimeout(record.timer);
                record = {source: sourceOf(media), attempts: 0, manual: false, timer: null};
                automatic.set(media, record);
            }
            return record;
        };
        const cancelAutomatic = function (media) {
            const record = automatic.get(media);
            if (record) { clearTimeout(record.timer); record.timer = null; }
        };
        const reportFailure = function (media, name) {
            const failure = {name: name, readyState: media.readyState, muted: media.muted,
                attempt: recordFor(media).attempts, time: Date.now()};
            state.failures.push(failure);
            if (state.failures.length > 20) state.failures.shift();
            try { if (window.reelBreakPlaybackFailure) window.reelBreakPlaybackFailure(JSON.stringify(failure)); } catch (_) {}
        };
        let selected = null, scheduled = false;
        const isReels = function () {
            return /(^|\.)instagram\.com$/i.test(window.location.hostname) && /^\/reels?(\/|$)/.test(window.location.pathname);
        };
        const visibleArea = function (media) {
            if (!media.isConnected || media.ended) return 0;
            const rect = media.getBoundingClientRect();
            const style = window.getComputedStyle(media);
            if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return 0;
            return Math.max(0, Math.min(rect.right, window.innerWidth) - Math.max(rect.left, 0)) *
                Math.max(0, Math.min(rect.bottom, window.innerHeight) - Math.max(rect.top, 0));
        };
        const currentVideo = function () {
            let best = null, area = 0;
            document.querySelectorAll('video').forEach(function (media) {
                const candidate = visibleArea(media);
                if (candidate > area) { best = media; area = candidate; }
            });
            return best;
        };
        const eligible = function (media) {
            return media.isConnected && !media.ended && (media.tagName !== 'VIDEO' || media === currentVideo());
        };
        const notifyChildren = function () {
            Array.from(window.frames || []).forEach(function (frame) {
                try { frame.postMessage({ reelBreakVisibility: true, hidden: state.hidden }, '*'); } catch (_) {}
            });
        };
        const updateRetryButton = function () {
            if (state.hidden || !state.lastError || !Array.from(state.paused).some(eligible)) {
                if (state.retryButton) state.retryButton.remove();
                state.retryButton = null;
                return;
            }
            if (state.retryButton || !document.documentElement) return;
            const button = document.createElement('button');
            button.textContent = 'Play / Retry video';
            button.setAttribute('type', 'button');
            button.setAttribute('aria-label', 'Resume video playback');
            button.style.cssText = 'all:initial;position:fixed;bottom:20px;right:20px;z-index:2147483647;padding:12px 18px;border-radius:8px;background:#2762df;color:white;font:14px sans-serif;cursor:pointer;box-shadow:0 2px 12px #0006;';
            button.addEventListener('click', function () { retry(true); });
            document.documentElement.appendChild(button);
            state.retryButton = button;
        };
        const cancelAttempt = function (media) {
            const token = state.attempting.get(media);
            if (token) { clearTimeout(token.timer); state.attempting.delete(media); }
        };
        const attempt = function (media, force, mutedFallback) {
            if (!eligible(media)) { state.paused.delete(media); cancelAttempt(media); return; }
            if (state.hidden) return;
            if (force) { cancelAttempt(media); media.pause(); }
            if (state.attempting.has(media)) return;
            const token = {source: media.src || media.currentSrc || ''};
            state.attempting.set(media, token);
            state.resumeAttempts++;
            const finish = function () {
                if (state.attempting.get(media) !== token) return false;
                clearTimeout(token.timer); state.attempting.delete(media);
                return true;
            };
            const succeeded = function () {
                if (!finish()) return;
                if (state.hidden || !eligible(media)) {
                    media.pause();
                    if (state.hidden) state.paused.add(media); else state.paused.delete(media);
                } else if (!media.paused) {
                    state.paused.delete(media);
                    if (!state.paused.size) state.lastError = null;
                }
                if (media.paused) queueAutomatic(media);
                updateRetryButton();
            };
            const rejected = function (error) {
                if (!finish()) return;
                reportFailure(media, (error && error.name) || 'PlaybackError');
                if (error && error.name === 'NotAllowedError' && media.tagName === 'VIDEO' && !media.muted && !mutedFallback && isReels() && !state.hidden && eligible(media)) {
                    // Muted inline video can autoplay without inventing a user gesture.
                    media.muted = true; media.playsInline = true;
                    attempt(media, false, true);
                    return;
                }
                if (media.isConnected && !media.ended) state.paused.add(media);
                state.lastError = (error && error.name) || 'PlaybackError';
                if (state.lastError !== 'NotAllowedError' && state.lastError !== 'NotSupportedError') queueAutomatic(media);
                updateRetryButton();
            };
            token.timer = setTimeout(function () {
                if (!finish()) return;
                media.pause();
                state.paused.add(media);
                state.lastError = 'PlaybackTimeout';
                reportFailure(media, state.lastError);
                queueAutomatic(media);
                updateRetryButton();
            }, 8000);
            try {
                const result = media.play();
                if (result && typeof result.then === 'function') result.then(succeeded, rejected);
                else succeeded();
            } catch (error) { rejected(error); }
        };
        const autoTry = function (media) {
            const record = recordFor(media);
            record.timer = null;
            if (state.hidden || scrolling || !eligible(media) || record.manual || record.attempts >= 4 || media.readyState < 2 || !media.paused) return;
            if (!isReels() && !media.autoplay) return;
            if (state.attempting.has(media)) return;
            record.attempts++;
            state.paused.add(media);
            attempt(media, false, false);
        };
        const queueAutomatic = function (media) {
            const record = recordFor(media);
            if (state.hidden || !eligible(media) || record.manual || record.attempts >= 4 || record.timer !== null || (!isReels() && !media.autoplay)) return;
            record.timer = setTimeout(function () { autoTry(media); }, [200, 300, 700, 1400][record.attempts]);
        };
        const retry = function (includeVisible) {
            if (state.hidden) return;
            if (includeVisible) {
                const current = currentVideo();
                if (current) {
                    cancelAutomatic(current);
                    const record = recordFor(current); record.manual = false; record.attempts = 0;
                    state.paused.add(current);
                }
            }
            Array.from(state.paused).forEach(function (media) { attempt(media, includeVisible, false); });
            updateRetryButton();
        };
        const syncCurrent = function () {
            scheduled = false;
            if (state.hidden) return;
            const current = currentVideo();
            if (selected !== current) {
                if (selected) {
                    cancelAutomatic(selected);
                    if (isReels()) { cancelAttempt(selected); selected.pause(); state.paused.delete(selected); }
                }
                selected = current;
                if (current) {
                    cancelAutomatic(current);
                    const record = recordFor(current); record.attempts = 0; record.manual = false;
                }
            }
            const pending = current && state.attempting.get(current);
            if (pending && pending.source !== sourceOf(current)) { cancelAttempt(current); current.pause(); }
            if (current && current.paused && (isReels() || current.autoplay)) queueAutomatic(current);
            updateRetryButton();
        };
        const schedule = function () {
            if (!scheduled) { scheduled = true; requestAnimationFrame(syncCurrent); }
        };
        state.setHidden = function (value) {
            const wasHidden = state.hidden;
            state.hidden = value;
            if (value) {
                document.querySelectorAll('video, audio').forEach(function (media) {
                    if (!media.paused && !media.ended) { state.paused.add(media); media.pause(); }
                    cancelAttempt(media); cancelAutomatic(media);
                });
            } else if (wasHidden) { retry(false); schedule(); }
            updateRetryButton(); notifyChildren();
        };
        window.__reelBreakRetryMedia = retry;
        document.addEventListener('play', function (event) {
            const media = event.target;
            if (media && typeof media.pause === 'function' && (state.hidden || (isReels() && media.tagName === 'VIDEO' && !eligible(media)))) {
                if (state.hidden) state.paused.add(media);
                media.pause();
            }
        }, true);
        document.addEventListener('playing', function (event) {
            if (!state.hidden) {
                cancelAutomatic(event.target);
                if (userIntent && userIntent.media === event.target) userIntent = null;
                state.paused.delete(event.target);
                if (!state.paused.size) state.lastError = null;
                updateRetryButton();
            }
        }, true);
        document.addEventListener('click', function (event) {
            if (!event.isTrusted || event.target === state.retryButton) return;
            const current = currentVideo();
            if (current && event.target.closest && event.target.closest('video,button,[role="button"]')) {
                userIntent = {media: current, source: sourceOf(current), time: Date.now()};
            }
        }, true);
        document.addEventListener('keydown', function (event) {
            if (event.isTrusted && [' ', 'k', 'K'].includes(event.key)) {
                const current = currentVideo();
                if (current) userIntent = {media: current, source: sourceOf(current), time: Date.now()};
            }
        }, true);
        document.addEventListener('pause', function (event) {
            const media = event.target;
            if (state.hidden || !eligible(media)) return;
            const record = recordFor(media);
            if (userIntent && userIntent.media === media && userIntent.source === sourceOf(media) && Date.now() - userIntent.time < 500) {
                record.manual = true; cancelAutomatic(media); cancelAttempt(media); state.paused.delete(media);
                userIntent = null;
            } else queueAutomatic(media);
        }, true);
        document.addEventListener('emptied', function (event) {
            cancelAutomatic(event.target); cancelAttempt(event.target); automatic.delete(event.target); schedule();
        }, true);
        ['loadeddata', 'canplay', 'loadedmetadata'].forEach(function (name) {
            document.addEventListener(name, schedule, true);
        });
        document.addEventListener('load', function (event) {
            if (event.target && event.target.tagName === 'IFRAME') notifyChildren();
        }, true);
        document.addEventListener('scroll', function () {
            scrolling = true; clearTimeout(scrollTimer);
            if (selected) cancelAutomatic(selected);
            scrollTimer = setTimeout(function () { scrolling = false; schedule(); }, 180);
        }, {capture: true, passive: true});
        window.addEventListener('resize', schedule);
        window.addEventListener('popstate', schedule);
        if (typeof MutationObserver !== 'undefined') {
            new MutationObserver(schedule).observe(document, {subtree: true, childList: true, attributes: true, attributeFilter: ['src', 'autoplay']});
        }
        if (typeof IntersectionObserver !== 'undefined') {
            const observer = new IntersectionObserver(schedule, {threshold: [0, 0.25, 0.5, 0.75, 1]});
            const observeVideos = function () { document.querySelectorAll('video').forEach(function (media) { observer.observe(media); }); };
            observeVideos();
            new MutationObserver(observeVideos).observe(document, {subtree: true, childList: true});
        }
        window.addEventListener('message', function (event) {
            if (event.data && event.data.reelBreakVisibility === true && typeof event.data.hidden === 'boolean' && (event.source === window.parent || event.source === window)) {
                state.setHidden(event.data.hidden);
            }
        });
        schedule();
    }
    state.setHidden(hidden);
})(/*HIDDEN*/);
