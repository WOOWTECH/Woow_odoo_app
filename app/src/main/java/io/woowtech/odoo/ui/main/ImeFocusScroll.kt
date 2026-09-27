package io.woowtech.odoo.ui.main

import android.webkit.WebView
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity

/**
 * Decides when the WebView must be asked to bring its focused field above the soft keyboard.
 *
 * Why this exists: Chromium only scrolls the focused editable into view **once**, on the first
 * viewport shrink after the IME reports `RESULT_SHOWN`, and only if the window's visible display
 * frame changed since that report (`ImeAdapterImpl.onResizeScrollableViewport`). Under
 * edge-to-edge, Compose animates the IME inset frame by frame, so the WebView shrinks in many small
 * steps: the one-shot scroll either fires on the first, barely-shrunk frame (field still "visible",
 * nothing happens) and is then cancelled, or never fires because the visible frame already matched
 * when the IME result arrived. Either way the field ends up behind the keyboard.
 *
 * Feed it the IME bottom inset every time it changes; it returns `true` exactly once per keyboard
 * show, when the show animation has settled (current == target > 0). Hiding resets it.
 */
internal class ImeFocusScrollTrigger {
    private var shownAndSettled = false

    fun onImeInsets(currentBottomPx: Int, targetBottomPx: Int): Boolean {
        if (targetBottomPx <= 0) {
            shownAndSettled = false
            return false
        }
        if (currentBottomPx != targetBottomPx || shownAndSettled) return false
        shownAndSettled = true
        return true
    }
}

/**
 * Calls [onImeShown] once each time the soft keyboard finishes appearing (see
 * [ImeFocusScrollTrigger]). Reads insets through `snapshotFlow`, so the IME animation does not
 * recompose the caller.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImeShownEffect(onImeShown: () -> Unit) {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    val trigger = remember { ImeFocusScrollTrigger() }
    val latestOnImeShown by rememberUpdatedState(onImeShown)
    LaunchedEffect(ime, imeTarget, density) {
        snapshotFlow { ime.getBottom(density) to imeTarget.getBottom(density) }
            .collect { (current, target) ->
                if (trigger.onImeInsets(current, target)) latestOnImeShown()
            }
    }
}

/**
 * Scrolls the page's focused editable element (input / textarea / select / contenteditable /
 * iframe) into the middle of the visible viewport — only when it is currently outside it, so a
 * field Chromium already revealed does not jump. Runs after two animation frames and once more on
 * the next window resize (within 1 s) so it measures against the already-shrunk viewport even if
 * the renderer receives the new size after this script. Posted to the WebView so the native
 * layout pass that shrank it has completed first.
 */
internal fun scrollFocusedEditableIntoView(webView: WebView) {
    webView.post { webView.evaluateJavascript(FOCUSED_EDITABLE_SCROLL_JS, null) }
}

internal const val FOCUSED_EDITABLE_SCROLL_JS: String = """(function(){
var el=document.activeElement;
if(!el||el===document.body||el===document.documentElement)return 'none';
var t=(el.tagName||'').toLowerCase();
var nonText=/^(button|submit|reset|checkbox|radio|file|image|range|color|hidden)$/i;
var editable=el.isContentEditable||t==='textarea'||t==='select'||t==='iframe'||(t==='input'&&!nonText.test(el.type||''));
if(!editable)return 'skip';
var go=function(){
if(document.activeElement!==el)return;
var r=el.getBoundingClientRect();
var vv=window.visualViewport;
var top=vv?vv.offsetTop:0;
var h=vv?vv.height:window.innerHeight;
if(r.top>=top&&r.bottom<=top+h)return;
try{el.scrollIntoView({block:'center',inline:'nearest'});}catch(e){el.scrollIntoView(false);}
};
requestAnimationFrame(function(){requestAnimationFrame(go);});
var onResize=function(){window.removeEventListener('resize',onResize);go();};
window.addEventListener('resize',onResize);
setTimeout(function(){window.removeEventListener('resize',onResize);},1000);
return 'scroll';
})();"""
