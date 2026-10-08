/**
 * AgentX Bridge background script (v3).
 *
 * Relay between native Android and page content scripts:
 * - Opens a native port via browser.runtime.connectNative("agentx"); the
 *   native MessageDelegate receives onConnect(port) and uses it to send
 *   action envelopes {msg, reqId} at any time.
 * - Forwards each envelope to the active tab's content script via
 *   browser.tabs.sendMessage, posts {reqId, resp} back on the port.
 * - Content -> native pushes (sendNativeMessage) bypass this script.
 */

(function () {
    "use strict";

    let nativePort = null;

    function openPort() {
        try {
            nativePort = browser.runtime.connectNative("agentx");
        } catch (e) {
            // Native side not ready; retry shortly.
            setTimeout(openPort, 1000);
            return;
        }
        nativePort.onDisconnect.addListener(() => {
            nativePort = null;
            setTimeout(openPort, 1000);
        });
        nativePort.onMessage.addListener((envelope) => {
            // envelope: {msg, reqId} — resolve the active tab ourselves so
            // native never has to track extension tab ids.
            browser.tabs.query({ active: true, currentWindow: true }).then(
                (tabs) => {
                    const tab = tabs && tabs[0];
                    if (!tab || tab.id == null) {
                        nativePort.postMessage({ reqId: envelope.reqId, error: "no active tab" });
                        return;
                    }
                    browser.tabs.sendMessage(tab.id, envelope.msg).then(
                        (resp) => nativePort.postMessage({ reqId: envelope.reqId, resp }),
                        (err) => nativePort.postMessage({
                            reqId: envelope.reqId,
                            error: String((err && err.message) || err),
                        })
                    );
                },
                (err) => nativePort.postMessage({
                    reqId: envelope.reqId,
                    error: String((err && err.message) || err),
                })
            );
        });
    }

    openPort();
})();
