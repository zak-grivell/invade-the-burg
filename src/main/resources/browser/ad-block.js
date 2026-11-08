(function () {
    'use strict';
    // Lightweight ad blocker for embedded browser — request interception, response
    // filtering (Instagram feed/reels APIs) and DOM filtering (sponsored reels).
    // Runs before page content loads via addOnBeforeLoad injection.

    // --- 1. Network request interception (fetch + XMLHttpRequest) ---
    // Entries without "/" match the host (and subdomains); entries with "/" match host+path prefix.
    const adDomains = [
        'doubleclick.net', 'googlesyndication.com', 'googleadservices.com',
        'adservice.google.com', 'ads.youtube.com', 'youtube.com/ads',
        'facebook.com/tr', 'ads.tiktok.com', 'analytics.tiktok.com', 'tiktok.com/pixel',
        'sharpspring.com', 'rubiconproject.com', 'openx.net', 'casalemedia.com',
        'pubmatic.com', 'appnexus.com', 'adnxs.com', 'criteo.com', 'criteo.net',
        'outbrain.com', 'taboola.com', 'mgnl.com', 'amazon-adsystem.com',
        'quantserve.com', 'scorecardresearch.com', 'chartbeat.com',
        'googletagmanager.com', 'google-analytics.com',
        'adservicelibrary.com', '2mdn.net', '33across.com',
        'adsafeprotected.com', 'adtech.com', 'exoclick.com', 'propellerads.com',
        'popads.net', 'trafficjunky.com', 'zeroclickfraud.net',
        'mixpanel.com', 'segment.com', 'hotjar.com'
    ];

    const isAdRequest = function (rawUrl) {
        try {
            const u = new URL(String(rawUrl), location.href);
            const host = u.hostname.toLowerCase().replace(/^www\./, '');
            const path = u.pathname.toLowerCase();
            const hostPath = host + path;
            for (let i = 0; i < adDomains.length; i++) {
                const d = adDomains[i];
                if (d.indexOf('/') !== -1) {
                    if (hostPath.indexOf(d) === 0) return true;
                } else if (host === d || host.endsWith('.' + d)) {
                    return true;
                }
            }
            // Generic tracker paths — third-party only, so first-party app routes aren't broken
            if (u.origin !== location.origin &&
                /\/(ads?|track|pixel|beacon|analytics|collect)(\/|$)/.test(path)) return true;
        } catch (_) {}
        return false;
    };

    // Instagram serves ads inline from its OWN API (first-party), so domain blocking can't
    // catch them. Instead, strip ad entries out of the JSON the feed/reels endpoints return.
    const isIG = /(^|\.)instagram\.com$/.test(location.hostname);
    const isFeedApi = function (rawUrl) {
        try {
            const u = new URL(String(rawUrl), location.href);
            if (!/(^|\.)instagram\.com$/.test(u.hostname)) return false;
            const path = u.pathname;
            // Match GraphQL, REST API v1, and XDT API paths (used for reels/feed)
            return /\/(graphql|xdt_api|api\/v\d+)\b/.test(path);
        } catch (_) { return false; }
    };

    const looksLikeAd = function (o, depth) {
        if (!o || typeof o !== 'object' || Array.isArray(o) || depth > 5) return false;
        // Direct ad indicators
        if (o.is_ad === true || o.ad_id || o.ad_action || o.product_type === 'ad') return true;
        // node.ad / item.ad — Instagram marks ads with an 'ad' property containing ad metadata
        if (o.ad && typeof o.ad === 'object') return true;
        // node.media.ad_id / node.media.is_sponsored — backup checks for media-level ad markers
        if (o.media && typeof o.media === 'object' && (o.media.ad_id || o.media.is_sponsored === true || o.media.product_type === 'ad')) return true;
        // Paid partnership / branded content (Instagram's primary ad marking)
        if (o.is_paid_partnership === true) return true;
        if (o.partnership_details && typeof o.partnership_details === 'object') return true;
        if (o.sponsor_tags && Array.isArray(o.sponsor_tags) && o.sponsor_tags.length > 0) return true;
        if (o.branded_content_info && typeof o.branded_content_info === 'object') return true;
        if (o.branded_content_tagged_users && Array.isArray(o.branded_content_tagged_users) && o.branded_content_tagged_users.length > 0) return true;
        if (o.payment_disclosure_label) return true;
        // Sponsored data from feed APIs
        if (o.sponsored_data && typeof o.sponsored_data === 'object') return true;
        if (o.is_sponsored === true) return true;
        // Recurse into nested objects
        for (const k in o) {
            const v = o[k];
            if (v && typeof v === 'object' && !Array.isArray(v) && looksLikeAd(v, depth + 1)) return true;
        }
        return false;
    };

    // Mutates in place; returns true if anything was removed.
    // Handles Instagram GraphQL structure: edges[].node, items[], media[], etc.
    const scrub = function (root) {
        let changed = false;
        (function walk(n) {
            if (Array.isArray(n)) {
                for (let i = n.length - 1; i >= 0; i--) {
                    if (looksLikeAd(n[i], 0)) { n.splice(i, 1); changed = true; }
                    else walk(n[i]);
                }
            } else if (n && typeof n === 'object') {
                // Handle Instagram GraphQL edges structure: { edges: [{ node: {...} }] }
                if (n.edges && Array.isArray(n.edges)) {
                    for (let i = n.edges.length - 1; i >= 0; i--) {
                        const edge = n.edges[i];
                        if (edge && typeof edge === 'object' && edge.node && looksLikeAd(edge.node, 0)) {
                            n.edges.splice(i, 1);
                            changed = true;
                        } else if (edge) {
                            walk(edge);
                        }
                    }
                }
                // Handle items array (REST API structure)
                else if (n.items && Array.isArray(n.items)) {
                    for (let i = n.items.length - 1; i >= 0; i--) {
                        if (looksLikeAd(n.items[i], 0)) {
                            n.items.splice(i, 1);
                            changed = true;
                        } else {
                            walk(n.items[i]);
                        }
                    }
                }
                // Handle media array
                else if (n.media && Array.isArray(n.media)) {
                    for (let i = n.media.length - 1; i >= 0; i--) {
                        if (looksLikeAd(n.media[i], 0)) {
                            n.media.splice(i, 1);
                            changed = true;
                        } else {
                            walk(n.media[i]);
                        }
                    }
                }
                // Generic object walk
                else {
                    for (const k in n) walk(n[k]);
                }
            }
        })(root);
        return changed;
    };

    const scrubText = function (text) {
        try {
            const json = JSON.parse(text);
            return scrub(json) ? JSON.stringify(json) : text;
        } catch (_) { return text; }
    };

    // Intercept fetch()
    const originalFetch = window.fetch;
    window.fetch = function (input) {
        const url = input && input.url ? input.url : String(input);
        if (isAdRequest(url)) {
            return Promise.resolve(new Response(null, { status: 204, statusText: 'Blocked' }));
        }
        const p = originalFetch.apply(this, arguments);
        if (!isIG || !isFeedApi(url)) return p;
        return p.then(async function (res) {
            try {
                const ct = res.headers.get('content-type') || '';
                if (!/json|javascript|text\/plain/.test(ct)) return res;
                const text = await res.clone().text();
                const out = scrubText(text);
                if (out === text) return res;
                const headers = new Headers(res.headers);
                headers.delete('content-length');
                return new Response(out, { status: res.status, statusText: res.statusText, headers: headers });
            } catch (_) {
                return res;
            }
        });
    };

    // Intercept XMLHttpRequest
    const originalOpen = XMLHttpRequest.prototype.open;
    const originalSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
        this._adBlockUrl = url == null ? '' : String(url);
        const ret = originalOpen.apply(this, arguments);
        if (isIG && isFeedApi(this._adBlockUrl)) {
            // Registered at open() time so it runs before the page's own listeners.
            this.addEventListener('readystatechange', function () {
                if (this.readyState !== 4) return;
                try {
                    if (this.responseType === '' || this.responseType === 'text') {
                        const text = this.responseText;
                        const out = scrubText(text);
                        if (out !== text) {
                            Object.defineProperty(this, 'responseText', { value: out, configurable: true });
                            Object.defineProperty(this, 'response', { value: out, configurable: true });
                        }
                    } else if (this.responseType === 'json' && this.response) {
                        scrub(this.response); // in place
                    }
                } catch (_) {}
            });
        }
        return ret;
    };
    XMLHttpRequest.prototype.send = function () {
        if (this._adBlockUrl && isAdRequest(this._adBlockUrl)) {
            // status/readyState are read-only, so assigning to them does nothing.
            // Re-point the request at an empty data: URL so it completes (or errors) normally.
            try {
                originalOpen.call(this, 'GET', 'data:text/plain,');
                return originalSend.call(this);
            } catch (_) {
                return;
            }
        }
        return originalSend.apply(this, arguments);
    };

    // --- 1b. Global JSON interception — catches ALL deserialized API responses ---
    // Instagram uses Relay cache, preloaded data, and other mechanisms that bypass
    // fetch/XHR interception. Hooking JSON.parse and Response.json catches everything.
    const originalParse = JSON.parse;
    const originalResponseJson = Response.prototype.json;

    // Deep recursive clean for all possible API response paths
    const deepClean = function (obj, depth) {
        if (!obj || typeof obj !== 'object' || depth > 10) return;
        depth = depth || 0;
        // Clean edges at any level
        if (obj.edges && Array.isArray(obj.edges)) {
            for (let i = obj.edges.length - 1; i >= 0; i--) {
                const edge = obj.edges[i];
                if (edge && typeof edge === 'object' && edge.node && looksLikeAd(edge.node, 0)) {
                    obj.edges.splice(i, 1);
                }
            }
        }
        // Clean items array
        if (obj.items && Array.isArray(obj.items)) {
            for (let i = obj.items.length - 1; i >= 0; i--) {
                if (looksLikeAd(obj.items[i], 0)) {
                    obj.items.splice(i, 1);
                }
            }
        }
        // Clean media array
        if (obj.media && Array.isArray(obj.media)) {
            for (let i = obj.media.length - 1; i >= 0; i--) {
                if (looksLikeAd(obj.media[i], 0)) {
                    obj.media.splice(i, 1);
                }
            }
        }
        // Clean story ad media items
        if (obj.ad_media_items && Array.isArray(obj.ad_media_items)) {
            obj.ad_media_items = [];
        }
        // Recurse into known paths
        if (obj.data) deepClean(obj.data, depth + 1);
        if (obj.result) deepClean(obj.result, depth + 1);
        if (obj.__bbox) deepClean(obj.__bbox, depth + 1);
        if (obj.node) deepClean(obj.node, depth + 1);
        if (obj.media && typeof obj.media === 'object' && !Array.isArray(obj.media)) deepClean(obj.media, depth + 1);
        // Recurse into require array (Relay preloaded data)
        if (Array.isArray(obj.require)) {
            for (let i = 0; i < obj.require.length; i++) {
                const req = obj.require[i];
                if (Array.isArray(req)) {
                    for (let j = 0; j < req.length; j++) {
                        deepClean(req[j], depth + 1);
                    }
                }
            }
        }
        // Generic recursion for any remaining nested objects
        for (const k in obj) {
            if (k === 'edges' || k === 'items' || k === 'media' || k === 'ad_media_items' ||
                k === 'data' || k === 'result' || k === '__bbox' || k === 'node' || k === 'require') continue;
            const v = obj[k];
            if (v && typeof v === 'object') {
                deepClean(v, depth + 1);
            }
        }
    };

    const cleanAll = function (obj) {
        if (!obj || typeof obj !== 'object') return obj;
        // Run the deep recursive clean on all paths
        deepClean(obj, 0);
        // Also run the existing scrub for edges/items/media handling
        scrub(obj);
        return obj;
    };

    // Only hook on Instagram to avoid breaking other sites
    if (isIG) {
        JSON.parse = function (text, reviver) {
            const data = originalParse.call(JSON, text, reviver);
            try { cleanAll(data); } catch (_) {}
            return data;
        };

        Response.prototype.json = async function () {
            const data = await originalResponseJson.call(this);
            try { cleanAll(data); } catch (_) {}
            return data;
        };
    }

    // --- 2. Cosmetic filtering ---
    const HIDE_ATTR = 'data-igab-hidden';

    const injectStyle = function () {
        if (document.getElementById('igab-style')) return;
        const root = document.head || document.documentElement;
        if (!root) return;
        const s = document.createElement('style');
        s.id = 'igab-style';
        s.textContent =
            '[' + HIDE_ATTR + '], ins.adsbygoogle, [id^="google_ads_iframe"], ' +
            'iframe[src*="doubleclick.net"], iframe[src*="googlesyndication.com"], ' +
            '[data-ad-id], [data-promoted] { display: none !important; } ' +
            // Instagram-specific: hide sponsored/paid partnership labels and their containers
            'span:has-text("Sponsored"), span:has-text("Paid partnership"), ' +
            '[class*="_8ESGk"], [class*="_8ESGk "], ' +
            '[data-testid="sponsored-label"], [data-testid="paid-partnership"], ' +
            '[data-testid="sponsored-canvas"] { display: none !important; }';
        root.appendChild(s);
    };

    // Instagram's "Sponsored" label in a handful of locales, plus "Paid partnership" variants
    const SPONSORED = new Set([
        'sponsored', 'gesponsert', 'sponsorisé', 'patrocinado', 'sponsorizzato',
        'gesponsord', 'sponsorowane', 'sponsrad', 'sponsoroitu', 'реклама',
        '広告', '광고', '赞助内容', '贊助',
        'paid partnership', 'paid partnership with',
        'partnerschaft mit', 'collaboration avec', 'colaboración con',
        'collaborazione con', 'samenwerking met', 'samarbete med',
        'партнёрство с', '提携', '파트너십', '合作'
    ]);

    const normalize = function (t) {
        return t.replace(/[\u200b-\u200f\u2060\ufeff]/g, '').trim().toLowerCase();
    };

    const hasSponsorLabel = function (container) {
        const els = container.querySelectorAll('span, a, div, p');
        for (let i = 0; i < els.length; i++) {
            const el = els[i];
            if (el.childElementCount > 8) continue;
            const t = el.textContent;
            if (!t || t.length > 80) continue;
            const normalized = normalize(t);
            // Check for exact matches (short labels like "Sponsored")
            if (t.length <= 20 && SPONSORED.has(normalized)) return true;
            // Check for partial matches (longer labels like "Paid partnership with @brand")
            for (const label of SPONSORED) {
                if (normalized.startsWith(label)) return true;
            }
        }
        return false;
    };

    // Climb from a <video> to the outermost ancestor that still holds only that one video:
    // that's the "slot" for a single reel (video + username/label + action buttons).
    // If the label sits outside this slot, widen the search to the nearest scroll-container ancestor.
    const getSlot = function (video) {
        let el = video;
        const maxH = window.innerHeight * 1.5;
        while (el.parentElement) {
            const p = el.parentElement;
            if (p === document.body || p === document.documentElement ||
                p.tagName === 'MAIN' || p.getAttribute('role') === 'main') break;
            if (p.querySelectorAll('video').length !== 1) break;
            if (p.offsetHeight > maxH) break;
            const oy = getComputedStyle(p).overflowY;
            if (oy === 'auto' || oy === 'scroll') break;
            el = p;
        }
        const slot = el === video ? video.parentElement : el;
        // If the slot doesn't contain a sponsor label, try widening to the next 1-2 ancestors
        // where the label might live (e.g. a wrapper that contains the slot + caption area).
        let current = slot;
        for (let i = 0; i < 3; i++) {
            if (current && hasSponsorLabel(current)) return current;
            current = current.parentElement;
        }
        return slot;
    };

    const hideSponsoredReels = function () {
        const videos = document.getElementsByTagName('video');
        for (let i = 0; i < videos.length; i++) {
            const v = videos[i];
            if (v.closest('[' + HIDE_ATTR + ']')) continue;
            const slot = getSlot(v);
            if (!slot || !hasSponsorLabel(slot)) continue;

            const r = slot.getBoundingClientRect();
            const inView = r.top < window.innerHeight * 0.6 && r.bottom > window.innerHeight * 0.4;
            slot.setAttribute(HIDE_ATTR, '');

            // If the ad is the reel currently on screen, move on to the next one.
            if (inView) {
                let next = slot.nextElementSibling;
                while (next && next.hasAttribute(HIDE_ATTR)) next = next.nextElementSibling;
                if (next) next.scrollIntoView({ block: 'start' });
            }
        }
        // A display:none video can keep playing audio — keep hidden ones paused and muted.
        const hiddenVideos = document.querySelectorAll('[' + HIDE_ATTR + '] video');
        for (let j = 0; j < hiddenVideos.length; j++) {
            try {
                if (!hiddenVideos[j].paused) hiddenVideos[j].pause();
                hiddenVideos[j].muted = true;
            } catch (_) {}
        }
    };

    const hideAds = function () {
        try { injectStyle(); } catch (_) {}
        try { hideSponsoredReels(); } catch (_) {}
    };

    // --- 3. Mutation observer for dynamically added ads (debounced) ---
    let scheduled = false;
    const schedule = function () {
        if (scheduled) return;
        scheduled = true;
        setTimeout(function () { scheduled = false; hideAds(); }, 150);
    };

    const observer = new MutationObserver(function (mutations) {
        for (let i = 0; i < mutations.length; i++) {
            if (mutations[i].addedNodes.length > 0) { schedule(); return; }
        }
    });

    // documentElement can be null when injected at document start
    const startObserver = function () {
        if (!document.documentElement) { setTimeout(startObserver, 10); return; }
        observer.observe(document.documentElement, { childList: true, subtree: true });
    };
    startObserver();

    // --- 4. Run on load ---
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', hideAds);
    } else {
        hideAds();
    }

    // Instagram loads reels content lazily — run additional scans after short delays
    // to catch sponsored labels that render after the initial video appears.
    setTimeout(hideAds, 500);
    setTimeout(hideAds, 1500);
    setTimeout(hideAds, 3000);

    // Re-run periodically for late-loaded content (e.g. a label that renders after the video)
    setInterval(hideAds, 1000);
})();