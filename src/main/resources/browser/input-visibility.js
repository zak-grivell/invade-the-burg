(function (active) {
    if (!window.__reelBreakSetBrowserActive) {
        let enabled = false;
        const block = function (event) {
            if (enabled) return;
            event.preventDefault();
            event.stopImmediatePropagation();
        };
        ['keydown', 'keyup', 'keypress', 'beforeinput'].forEach(function (name) {
            window.addEventListener(name, block, true);
        });
        const notifyChildren = function () {
            Array.from(window.frames || []).forEach(function (frame) {
                try { frame.postMessage({reelBreakInputActive: enabled}, '*'); } catch (_) {}
            });
        };
        window.addEventListener('message', function (event) {
            if (event.source === window.parent && typeof event.data?.reelBreakInputActive === 'boolean') window.__reelBreakSetBrowserActive(event.data.reelBreakInputActive);
        });
        document.addEventListener('load', function (event) { if (event.target?.tagName === 'IFRAME') notifyChildren(); }, true);
        window.__reelBreakSetBrowserActive = function (value) {
            enabled = Boolean(value);
            if (!enabled && document.activeElement && typeof document.activeElement.blur === 'function') document.activeElement.blur();
            notifyChildren();
        };
    }
    window.__reelBreakSetBrowserActive(active);
})(/*ACTIVE*/);
