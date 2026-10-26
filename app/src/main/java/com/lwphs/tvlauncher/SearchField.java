package com.lwphs.tvlauncher;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;

/**
 * The search box. With the on-screen keyboard closed, Down leaves the box right away.
 * Without this, Gboard takes that first Down (before the app sees it) to reopen the keyboard.
 */
public final class SearchField extends EditText {

    public SearchField(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.getAction() == KeyEvent.ACTION_DOWN) {
            WindowInsets insets = getRootWindowInsets();
            boolean keyboardOpen = insets != null && insets.isVisible(WindowInsets.Type.ime());
            View next = keyboardOpen ? null : getRootView().findViewById(getNextFocusDownId());
            if (next != null && next.isShown() && next.requestFocus()) return true;
        }
        return super.onKeyPreIme(keyCode, event);
    }
}
