package com.example.sltest;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** «Банк-тест»: имитирует банковское приложение — браузер (3-D Secure), выбор приложения (СБП), камера (QR), выбор файла. */
public class Main extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(40, 80, 40, 40);
        TextView t = new TextView(this); t.setText("Банк-тест: разрешённое приложение"); t.setTextSize(24); l.addView(t);
        EditText e = new EditText(this); e.setHint("Сумма"); l.addView(e);
        add(l, "Открыть 3-D Secure (браузер)", new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/3ds")));
        add(l, "Выбор приложения (СБП/Поделиться)", Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "оплата"), "Выберите банк"));
        add(l, "Камера (QR)", new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE));
        add(l, "Выбрать файл", new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE));
        add(l, "Открыть Часы (НЕ разрешено)", getPackageManager().getLaunchIntentForPackage("com.android.deskclock"));
        setContentView(l);
    }
    private void add(LinearLayout l, String s, Intent i) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false);
        b.setOnClickListener(v -> { try { startActivity(i); } catch (Exception ex) { ex.printStackTrace(); } });
        l.addView(b);
    }
}
