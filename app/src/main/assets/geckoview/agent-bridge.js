/**
 * AgentX GeckoView bridge content script (v2).
 *
 * Two channels:
 * 1. PUSH: sends fresh element tables to native via
 *    browser.runtime.sendNativeMessage("agentx", ...) on page load and on
 *    debounced DOM mutations. Native caches the latest table per session —
 *    the agent loop reads it with zero round trips (no screenshots, ever).
 * 2. REQUEST: listens via browser.runtime.onMessage for native-initiated
 *    actions (relayed through background.js): snapshot/click/type/scroll/state.
 */

(function () {
    "use strict";

    const SELECTORS = [
        "a[href]",
        "button",
        "input",
        "select",
        "textarea",
        "[role='button']",
        "[role='link']",
        "[role='checkbox']",
        "[role='radio']",
        "[role='switch']",
        "[role='textbox']",
        "[role='combobox']",
        "[role='menuitem']",
        "[onclick]",
    ].join(",");

    const MAX_ELEMENTS = 300;
    let refMap = new Map();
    let nextRef = 1;
    let pushTimer = null;

    function isVisible(el) {
        const r = el.getBoundingClientRect();
        if (r.width <= 0 || r.height <= 0) return false;
        const s = window.getComputedStyle(el);
        return s.visibility !== "hidden" && s.display !== "none";
    }

    function accessibleName(el) {
        return (
            el.getAttribute("aria-label") ||
            el.getAttribute("alt") ||
            el.getAttribute("title") ||
            el.getAttribute("placeholder") ||
            (el.innerText || "").trim().replace(/\s+/g, " ").slice(0, 80) ||
            (el.value || "").toString().slice(0, 80) ||
            ""
        );
    }

    function roleOf(el) {
        return (
            el.getAttribute("role") ||
            ({ A: "link", BUTTON: "button", SELECT: "combobox", TEXTAREA: "textbox" })[el.tagName] ||
            (el.tagName === "INPUT"
                ? (el.type === "checkbox" ? "checkbox" : el.type === "radio" ? "radio" : "textbox")
                : el.tagName.toLowerCase())
        );
    }

    function buildTable() {
        refMap = new Map();
        nextRef = 1;
        const lines = [];
        let count = 0;
        const els = document.querySelectorAll(SELECTORS);
        for (const el of els) {
            if (count >= MAX_ELEMENTS) break;
            if (!isVisible(el)) continue;
            const ref = nextRef++;
            refMap.set(ref, el);
            const name = accessibleName(el);
            const states = [];
            if (el.disabled) states.push("disabled");
            if (el.checked) states.push("checked");
            const v = (el.value || "").toString().trim().replace(/\s+/g, " ").slice(0, 60);
            lines.push(
                "[" + ref + "] " + roleOf(el) +
                (name ? " " + JSON.stringify(name) : "") +
                (v ? " · value=" + JSON.stringify(v) : "") +
                (states.length ? " · " + states.join(", ") : "")
            );
            count++;
        }
        if (count >= MAX_ELEMENTS) lines.push("… (truncated; table capped at " + MAX_ELEMENTS + ")");
        return { table: lines.join("\n"), url: location.href, title: document.title };
    }

    function pushTable() {
        try {
            const snap = buildTable();
            browser.runtime.sendNativeMessage("agentx", {
                type: "table",
                table: snap.table,
                url: snap.url,
                title: snap.title,
            });
        } catch (e) { /* native side not attached yet */ }
    }

    function schedulePush() {
        if (pushTimer) clearTimeout(pushTimer);
        pushTimer = setTimeout(pushTable, 400);
    }

    function handleAction(msg) {
        const el = msg.ref != null ? refMap.get(Number(msg.ref)) : null;
        switch (msg.type) {
            case "snapshot":
                return { type: "table", ...buildTable() };
            case "state":
                return { type: "state", url: location.href, title: document.title, readyState: document.readyState };
            case "click":
                if (!el) return { type: "done", ok: false, error: "unknown ref" };
                el.scrollIntoView({ block: "nearest" });
                el.click();
                schedulePush();
                return { type: "done", ok: true };
            case "type":
                if (!el) return { type: "done", ok: false, error: "unknown ref" };
                el.focus();
                try {
                    const proto = el.tagName === "TEXTAREA"
                        ? window.HTMLTextAreaElement.prototype
                        : window.HTMLInputElement.prototype;
                    Object.getOwnPropertyDescriptor(proto, "value").set.call(el, msg.text || "");
                } catch (e) { el.value = msg.text || ""; }
                el.dispatchEvent(new Event("input", { bubbles: true }));
                el.dispatchEvent(new Event("change", { bubbles: true }));
                schedulePush();
                return { type: "done", ok: true };
            case "scroll":
                if (el) el.scrollIntoView({ block: "center" });
                else window.scrollBy(0, msg.dy || 600);
                schedulePush();
                return { type: "done", ok: true };
            default:
                return { type: "done", ok: false, error: "unknown action" };
        }
    }

    // Native-initiated actions arrive via background.js relay.
    browser.runtime.onMessage.addListener((msg, sender, sendResponse) => {
        try { sendResponse(handleAction(msg)); }
        catch (e) { sendResponse({ type: "done", ok: false, error: String((e && e.message) || e) }); }
        return true;
    });

    // Push on load + debounced DOM mutations.
    if (document.readyState === "complete") pushTable();
    else window.addEventListener("load", pushTable);
    new MutationObserver(schedulePush).observe(document.documentElement, {
        childList: true, subtree: true, attributes: true,
        attributeFilter: ["disabled", "checked", "aria-expanded", "value"],
    });
})();
