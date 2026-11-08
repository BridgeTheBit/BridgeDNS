package dev.bridgedns.vpn;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

final class UiPalette {
    final int background, surface, card, line, text, muted, accent, accentSoft, accentStrong;
    final boolean dark;

    private UiPalette(boolean dark) {
        this.dark = dark;
        if (dark) {
            background = 0xff071a22;
            surface = 0xff0b2730;
            card = 0xff10323b;
            line = 0xff1b4650;
            text = 0xffedf7f6;
            muted = 0xffa5bcbd;
            accent = 0xff43dfc6;
            accentSoft = 0xff155c58;
            accentStrong = 0xffa2fff0;
        } else {
            background = 0xfff2faf8;
            surface = 0xffe7f4f0;
            card = 0xffffffff;
            line = 0xffd4e8e2;
            text = 0xff152e32;
            muted = 0xff60777a;
            accent = 0xff078d7b;
            accentSoft = 0xffd1f2eb;
            accentStrong = 0xff006b60;
        }
    }

    static String mode(Context context) {
        return context.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("theme", "system");
    }

    static boolean isDark(Context context) {
        String mode = mode(context);
        if ("dark".equals(mode)) return true;
        if ("light".equals(mode)) return false;
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    static UiPalette from(Context context) { return new UiPalette(isDark(context)); }

    static void setMode(Context context, String mode) {
        if (!"system".equals(mode) && !"light".equals(mode) && !"dark".equals(mode)) mode = "system";
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("theme", mode).apply();
    }

    static void apply(Activity activity) {
        activity.setTheme(isDark(activity) ? R.style.AppThemeDark : R.style.AppThemeLight);
    }
}
