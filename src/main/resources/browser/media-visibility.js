(function (hidden) {
    const key = '__reelBreakMediaState';
    let state = window[key];
    if (!state) {
        state = window[key] = {
            hidden: false, paused: new Set(), attempting: new Map(), lastError: null,
            resumeAttempts: 0, retryButton: null, failures: []
        };
        const prototype = HTMLMediaElement.prototype;
        const playDescriptor = Object.getOwnPropertyDescriptor(prototype, 'play');
        const pauseDescriptor = Object.getOwnPropertyDescriptor(prototype, 'pause');
        const nativePlay = prototype.play, nativePause = prototype.pause;
        const intents = new WeakMap(), retainedSources = new WeakMap();
        const sourceOf = media => media.src || media.currentSrc || '';
        const instagram = () => /(^|\.)instagram\.com$/i.test(window.location.hostname);
        const visibleArea = function (media) {
            if (!media.isConnected || media.ended) return 0;
            const rect = media.getBoundingClientRect();
            const style = window.getComputedStyle(media);
            if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return 0;
            return Math.max(0, Math.min(rect.right, window.innerWidth) - Math.max(rect.left, 0)) *
                Math.max(0, Math.min(rect.bottom, window.innerHeight) - Math.max(rect.top, 0));
        };
        const canResume = media => media.isConnected && !media.ended && (media.tagName !== 'VIDEO' || visibleArea(media) > 0);
        const currentVideo = function () {
            let best = null, area = 0;
            document.querySelectorAll('video').forEach(function (media) {
                const candidate = visibleArea(media);
                if (candidate > area) { best = media; area = candidate; }
            });
            return best;
        };
        const updateRetryButton = function () {
            if (state.hidden || !state.lastError || !Array.from(state.paused).some(canResume)) {
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
        const finish = function (media, token) {
            clearTimeout(token.timer);
            if (state.attempting.get(media) === token) state.attempting.delete(media);
        };
        const cancel = function (media) {
            const token = intents.get(media);
            if (token) finish(media, token);
            intents.delete(media);
        };
        const retain = function (media) {
            state.paused.add(media);
            retainedSources.set(media, sourceOf(media));
        };
        const report = function (media, error) {
            state.lastError = (error && error.name) || 'PlaybackError';
            const failure = {name: state.lastError, readyState: media.readyState, muted: media.muted, time: Date.now()};
            state.failures.push(failure);
            if (state.failures.length > 20) state.failures.shift();
            try { if (window.reelBreakPlaybackFailure) window.reelBreakPlaybackFailure(JSON.stringify(failure)); } catch (_) {}
            retain(media); updateRetryButton();
        };
        // Observe the player's actual request. Do not guess which Reel it should play from scroll geometry.
        const requestPlay = function (media) {
            cancel(media);
            const token = {source: sourceOf(media)};
            intents.set(media, token);
            state.attempting.set(media, token);
            state.resumeAttempts++;
            if (state.hidden) retain(media);
            token.timer = setTimeout(function () {
                finish(media, token);
                if (intents.get(media) === token && !state.hidden && media.paused) {
                    // A slow request is diagnostic only: never abort the site's pending play().
                    report(media, {name: 'PlaybackPending'});
                }
            }, 8000);
            const valid = () => intents.get(media) === token && token.source === sourceOf(media);
            const failed = function (error) {
                finish(media, token);
                if (valid() && !state.hidden) report(media, error);
                throw error;
            };
            const succeeded = function (value) {
                finish(media, token);
                if (valid()) {
                    if (state.hidden) { retain(media); nativePause.call(media); }
                    else if (!media.paused) {
                        state.paused.delete(media);
                        if (!state.paused.size) state.lastError = null;
                        updateRetryButton();
                    }
                }
                return value;
            };
            try {
                const result = nativePlay.call(media);
                if (state.hidden) nativePause.call(media);
                if (!result || typeof result.then !== 'function') return succeeded(result);
                return result.catch(function (error) {
                    if (valid() && !state.hidden && instagram() && media.tagName === 'VIDEO' && !media.muted && error.name === 'NotAllowedError') {
                        // Only repair a browser policy rejection of the site's chosen video.
                        media.muted = true; media.playsInline = true;
                        return nativePlay.call(media);
                    }
                    throw error;
                }).then(succeeded, failed);
            } catch (error) { return failed(error); }
        };
        Object.defineProperty(prototype, 'play', {...playDescriptor, value: function () { return requestPlay(this); }});
        Object.defineProperty(prototype, 'pause', {...pauseDescriptor, value: function () {
            // A site/user pause supersedes an older play or retained resume, including while hidden.
            cancel(this); state.paused.delete(this); retainedSources.delete(this);
            updateRetryButton();
            return nativePause.call(this);
        }});
        const resume = function (media) {
            try {
                const result = requestPlay(media);
                if (result && typeof result.catch === 'function') result.catch(function () {});
            } catch (_) { /* requestPlay recorded the failure; user retry remains available. */ }
        };
        const retry = function (includeVisible) {
            if (state.hidden) return;
            if (includeVisible) {
                const current = currentVideo();
                if (current) resume(current);
            } else {
                Array.from(state.paused).forEach(function (media) {
                    if (canResume(media) && retainedSources.get(media) === sourceOf(media)) resume(media);
                    else { cancel(media); state.paused.delete(media); }
                });
            }
            updateRetryButton();
        };
        const notifyChildren = function () {
            Array.from(window.frames || []).forEach(function (frame) {
                try { frame.postMessage({reelBreakVisibility: true, hidden: state.hidden}, '*'); } catch (_) {}
            });
        };
        state.setHidden = function (value) {
            const changed = state.hidden !== value;
            state.hidden = value;
            if (value) {
                document.querySelectorAll('video, audio').forEach(function (media) {
                    if ((!media.paused && !media.ended) || state.attempting.has(media)) retain(media);
                    cancel(media);
                    if (!media.paused) nativePause.call(media);
                });
            } else if (changed) retry(false);
            updateRetryButton(); notifyChildren();
        };
        window.__reelBreakRetryMedia = retry;
        document.addEventListener('play', function (event) {
            if (state.hidden && event.target && typeof event.target.pause === 'function') {
                retain(event.target); nativePause.call(event.target);
            }
        }, true);
        document.addEventListener('playing', function (event) {
            if (state.hidden) { retain(event.target); nativePause.call(event.target); }
            else { state.paused.delete(event.target); updateRetryButton(); }
        }, true);
        document.addEventListener('pause', function (event) {
            // Native controls do not necessarily call the JavaScript pause() method.
            if (!state.hidden && event.target.paused) {
                cancel(event.target); state.paused.delete(event.target); updateRetryButton();
            }
        }, true);
        ['emptied', 'ended'].forEach(function (name) {
            document.addEventListener(name, function (event) {
                const media = event.target, token = intents.get(media);
                if (name === 'ended' || (token && token.source !== sourceOf(media))) cancel(media);
                state.paused.delete(media); retainedSources.delete(media); updateRetryButton();
            }, true);
        });
        document.addEventListener('load', function (event) {
            if (event.target && event.target.tagName === 'IFRAME') notifyChildren();
        }, true);
        window.addEventListener('message', function (event) {
            if (event.data && event.data.reelBreakVisibility === true && typeof event.data.hidden === 'boolean' && (event.source === window.parent || event.source === window)) {
                state.setHidden(event.data.hidden);
            }
        });
    }
    state.setHidden(hidden);
})(/*HIDDEN*/);
