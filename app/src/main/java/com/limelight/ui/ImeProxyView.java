package com.limelight.ui;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * Invisible 1x1 editor view that bridges Android soft keyboards (IME) directly
 * to Moonlight's UTF-8 / virtual key stream without fullscreen extract UI.
 */
public class ImeProxyView extends View {
    public interface Listener {
        void onCommitText(String text);
        void onBackspace(int count);
        boolean onImeKeyEvent(KeyEvent event);
    }

    private Listener listener;

    public ImeProxyView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (listener != null && text != null && text.length() > 0) {
                    listener.onCommitText(text.toString());
                }
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (listener != null && beforeLength > 0) {
                    listener.onBackspace(beforeLength);
                }
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (listener != null && event != null && listener.onImeKeyEvent(event)) {
                    return true;
                }
                return super.sendKeyEvent(event);
            }
        };
    }
}
