package com.encatch.core

/**
 * JS injected into inline-presentation WebViews after page load, ported from the RN SDK's
 * `form-inline-webview-helpers.ts` (`INLINE_WEBVIEW_SIZING_FIX_SCRIPT`).
 *
 * Hosted form pages pin themselves to the WebView viewport (`height: 100%`), so once the native
 * inline view grows, the page can never measure/report a height smaller than the current
 * viewport — `form:resize` becomes a ratchet that only goes up. This forces `height: auto` on
 * the page chrome and posts freshly measured content heights (including smaller ones) through
 * `window.ReactNativeWebView.postMessage`, re-measuring on a ResizeObserver plus settle timers.
 */
const val INLINE_WEBVIEW_SIZING_FIX_SCRIPT: String = """
(function () {
  if (window.__encatchInlineSizingFixInstalled) {
    if (typeof window.__encatchMeasureInlineHeight === 'function') {
      window.__encatchMeasureInlineHeight();
    }
    return true;
  }
  window.__encatchInlineSizingFixInstalled = true;

  var STYLE_ID = 'encatch-inline-native-fix';
  if (!document.getElementById(STYLE_ID)) {
    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent =
      'html.encatch-inline-presentation, html.encatch-inline-presentation body { height: auto !important; min-height: 0 !important; overflow: visible !important; } ' +
      'html.encatch-inline-presentation .app-container { height: auto !important; min-height: 0 !important; overflow: visible !important; justify-content: flex-start !important; align-items: stretch !important; } ' +
      'html.encatch-inline-presentation .form-wrapper { overflow: visible !important; } ' +
      'html.encatch-inline-presentation #encatch-form-id { height: auto !important; max-height: none !important; min-height: 0 !important; overflow: visible !important; }';
    document.head.appendChild(style);
  }

  document.documentElement.classList.add('encatch-inline-presentation');

  function measureInlineHeight() {
    var candidates = [
      document.querySelector('.form-wrapper'),
      document.getElementById('encatch-form-id'),
      document.querySelector('.app-container'),
      document.body,
    ].filter(Boolean);

    var height = 0;
    for (var i = 0; i < candidates.length; i++) {
      var el = candidates[i];
      height = Math.max(
        height,
        el.scrollHeight || 0,
        el.offsetHeight || 0,
        el.getBoundingClientRect ? el.getBoundingClientRect().height : 0
      );
    }

    height = Math.ceil(height);
    if (height > 0 && window.ReactNativeWebView) {
      window.ReactNativeWebView.postMessage(JSON.stringify({
        type: 'form:resize',
        data: { height: height },
      }));
    }
    return height;
  }

  window.__encatchMeasureInlineHeight = measureInlineHeight;

  measureInlineHeight();
  requestAnimationFrame(measureInlineHeight);
  setTimeout(measureInlineHeight, 50);
  setTimeout(measureInlineHeight, 250);
  setTimeout(measureInlineHeight, 1000);

  if (typeof ResizeObserver !== 'undefined') {
    var target =
      document.querySelector('.form-wrapper') ||
      document.getElementById('encatch-form-id') ||
      document.querySelector('.app-container');
    if (target) {
      var observer = new ResizeObserver(function () {
        measureInlineHeight();
      });
      observer.observe(target);
      window.__encatchInlineResizeObserver = observer;
    }
  }

  return true;
})();
true;
"""
