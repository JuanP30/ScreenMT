package io.github.juanp30.screenmt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * El Mac, por cable, le pasa a la app cómo conectarse por wifi (su dirección, puerto y clave) sin abrirla.
 * Solo lo puede mandar adb: el receptor exige el permiso DUMP, que ninguna app normal tiene.
 */
public class Emparejar extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String clave = i.getStringExtra("clave");
        if (clave == null || clave.length() < 12) return;
        android.content.SharedPreferences.Editor e = c.getSharedPreferences("screenmt", Context.MODE_PRIVATE).edit()
                .putString("wifiClave", clave)
                .putInt("wifiPuerto", i.getIntExtra("puerto", 8730));
        String host = i.getStringExtra("host");
        if (host != null && !host.isEmpty()) e.putString("wifiHost", host);
        e.apply();
    }
}
