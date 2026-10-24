package io.github.juanp30.screenmt;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/**
 * Escribir en el Mac con el teclado de la tablet (también con el dictado por voz del teclado).
 *
 * El teclado de Android escribe en un campo invisible. Cada cambio se compara con lo último que se mandó:
 * lo que se borró va como «borrar» y lo nuevo como texto. Así funcionan igual el autocorrector, las
 * sugerencias y el dictado, que reemplazan palabras enteras. El campo siempre empieza con un espacio de
 * guarda para poder detectar «borrar» aunque esté vacío.
 *
 * Encima va una barra con las teclas del Mac que Android no tiene.
 */
final class Teclado {
    interface Envio {
        void texto(String s);
        void tecla(int codigo, String mods);
    }

    private static final String GUARDA = " ";
    private static final int BORRAR = 51, ENTER = 36;
    private final Context c;
    private final Envio envio;
    final EditText campo;
    final LinearLayout barra;
    private String base = GUARDA;
    private boolean callando;

    Teclado(Context c, Envio envio) {
        this.c = c;
        this.envio = envio;
        float dp = c.getResources().getDisplayMetrics().density;

        campo = new EditText(c);
        campo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        campo.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        campo.setSingleLine(true);
        campo.setAlpha(0f);
        campo.setText(GUARDA);
        campo.setSelection(GUARDA.length());
        campo.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            public void onTextChanged(CharSequence s, int a, int b, int d) { }
            public void afterTextChanged(Editable e) { sincronizar(e.toString()); }
        });
        campo.setOnEditorActionListener((v, accion, ev) -> { envio.tecla(ENTER, ""); return true; });
        campo.setOnKeyListener((v, k, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (k == KeyEvent.KEYCODE_ENTER) { envio.tecla(ENTER, ""); return true; }
            return false;
        });

        LinearLayout fila = new LinearLayout(c);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        fila.setPadding((int) (8 * dp), (int) (6 * dp), (int) (8 * dp), (int) (6 * dp));
        Object[][] teclas = {
                {"Esc", 53, ""}, {"Tab", 48, ""}, {"←", 123, ""}, {"↑", 126, ""}, {"↓", 125, ""}, {"→", 124, ""},
                {"⌫", BORRAR, ""}, {"↵", ENTER, ""}, {"Copiar", 8, "cmd"}, {"Pegar", 9, "cmd"}, {"Deshacer", 6, "cmd"},
                {"Todo", 0, "cmd"}, {"Buscar", 3, "cmd"}, {"Cerrar", -1, ""},
        };
        for (Object[] t : teclas) {
            String nombre = (String) t[0];
            int codigo = (Integer) t[1];
            String mods = (String) t[2];
            fila.addView(boton(nombre, v -> { if (codigo < 0) ocultar(); else envio.tecla(codigo, mods); }, dp));
        }
        HorizontalScrollView desliza = new HorizontalScrollView(c);
        desliza.setHorizontalScrollBarEnabled(false);
        desliza.addView(fila);
        barra = new LinearLayout(c);
        barra.setBackgroundColor(0xF0181B21);
        barra.setGravity(Gravity.CENTER);
        barra.addView(desliza, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        barra.setVisibility(View.GONE);
    }

    private Button boton(String s, View.OnClickListener l, float dp) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.WHITE);
        b.setMinWidth((int) (56 * dp));
        b.setMinimumWidth((int) (56 * dp));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(8 * dp);
        g.setColor(0xFF2E333C);
        b.setBackground(g);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int) (44 * dp));
        lp.rightMargin = (int) (6 * dp);
        b.setLayoutParams(lp);
        b.setPadding((int) (14 * dp), 0, (int) (14 * dp), 0);
        return b;
    }

    private void sincronizar(String ahora) {
        if (callando) return;
        int p = 0, max = Math.min(ahora.length(), base.length());
        while (p < max && ahora.charAt(p) == base.charAt(p)) p++;
        for (int i = p; i < base.length(); i++) envio.tecla(BORRAR, "");
        if (p < ahora.length()) envio.texto(ahora.substring(p));
        base = ahora;
        // si se borró la guarda, o el campo creció, se vuelve a empezar (sin mandar nada)
        boolean sinComponer = android.view.inputmethod.BaseInputConnection.getComposingSpanStart(campo.getText()) < 0;
        if (!ahora.startsWith(GUARDA) || (ahora.length() > 40 && sinComponer)) reiniciar();
    }

    private void reiniciar() {
        callando = true;
        campo.setText(GUARDA);
        campo.setSelection(GUARDA.length());
        base = GUARDA;
        callando = false;
    }

    boolean visible() { return barra.getVisibility() == View.VISIBLE; }

    void mostrar() {
        reiniciar();
        barra.setVisibility(View.VISIBLE);
        campo.requestFocus();
        InputMethodManager im = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        campo.post(() -> im.showSoftInput(campo, InputMethodManager.SHOW_IMPLICIT));
    }

    void ocultar() {
        barra.setVisibility(View.GONE);
        InputMethodManager im = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        im.hideSoftInputFromWindow(campo.getWindowToken(), 0);
        campo.clearFocus();
    }
}
