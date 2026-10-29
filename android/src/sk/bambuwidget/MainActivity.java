package sk.bambuwidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Context;
import android.content.res.Configuration;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

/** Sign-in and a simple status list; the widget itself lives on the home screen. */
public class MainActivity extends Activity {
    private LinearLayout root;
    private boolean dark;
    /** On a sign-in screen: kept when the user switches to the mail / authenticator app and back. */
    private boolean signingIn;
    /** Sign-in opened from Accounts › Add account (can be cancelled). */
    private boolean adding;
    private int fg, muted;

    /** Dark or light, from the app setting or (for "system") the phone's night mode. */
    static boolean isDark(Context c) {
        String t = Store.theme(c);
        if ("dark".equals(t)) return true;
        if ("light".equals(t)) return false;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
    private final Cloud.Login login = new Cloud.Login();

    @Override
    protected void onCreate(Bundle b) {
        dark = isDark(this);
        setTheme(dark ? android.R.style.Theme_Material : android.R.style.Theme_Material_Light);
        fg = dark ? 0xFFE6E8EC : 0xFF1D1F24;
        muted = dark ? 0xFF9097A3 : 0xFF5C6270;
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(dark ? 0xFF15171C : 0xFFF5F6F8);
        sv.setFitsSystemWindows(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (signingIn) return;
        if (!Store.signedIn(this)) showLogin(Store.accounts(this).isEmpty() ? null : "Your sign-in has expired. Please sign in again.");
        else showStatus();
    }

    // ---------- UI helpers ----------
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(6), 0, dp(6));
        root.addView(t);
        return t;
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setTextColor(fg);
        e.setHintTextColor(muted);
        e.setSingleLine(type != (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        root.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private Button button(String label, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        root.addView(btn, lp);
        return btn;
    }

    /** Runs network work off the UI thread. */
    private interface Work { void run() throws Exception; }

    private void background(final Button btn, final TextView error, final Work work, final Runnable done) {
        btn.setEnabled(false);
        error.setText("");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try { work.run(); } catch (Exception e) { err = e.getMessage() != null ? e.getMessage() : e.toString(); }
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btn.setEnabled(true);
                        if (fErr != null) error.setText(fErr); else done.run();
                    }
                });
            }
        }).start();
    }

    private void signedIn(Cloud.Session s, String label) {
        Store.addAccount(this, s, label);
        adding = false;
        Updater.schedule(this);
        Store.setRefreshing(this, true);
        refreshNow();
    }

    /** Cancel button on the sign-in screens while adding a further account. */
    private void cancelButton() {
        if (!adding || !Store.signedIn(this)) return;
        button("Cancel", new View.OnClickListener() {
            @Override
            public void onClick(View v) { adding = false; showStatus(); }
        });
    }

    // ---------- screens ----------
    private void showLogin(String message) {
        root.removeAllViews();
        signingIn = true;
        text(adding ? "Add a Bambu Lab account" : "Sign in to Bambu Lab", 20, fg, true);
        if (adding) text("Printers of all accounts are shown together in the widget.", 14, muted, false);
        final EditText email = field("Email", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        final EditText password = field("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final CheckBox china = new CheckBox(this);
        china.setText("China account (bambulab.cn)");
        china.setTextColor(muted);
        root.addView(china);
        final TextView error = new TextView(this);
        final Button go = button("Sign in", null);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Cloud.Session[] out = new Cloud.Session[1];
                background(go, error, new Work() {
                    public void run() throws Exception {
                        out[0] = login.start(email.getText().toString(), password.getText().toString(), china.isChecked() ? "china" : "global");
                    }
                }, new Runnable() {
                    public void run() {
                        if (out[0] != null) { signedIn(out[0], login.email); showStatus(); }
                        else showCode(login.need);
                    }
                });
            }
        });
        button("Sign in with a token instead", new View.OnClickListener() {
            @Override
            public void onClick(View v) { showToken(); }
        });
        cancelButton();
        error.setTextColor(0xFFDC3C3C);
        error.setPadding(0, dp(10), 0, 0);
        if (message != null) error.setText(message);
        root.addView(error);
    }

    private void showCode(final String kind) {
        root.removeAllViews();
        signingIn = true;
        boolean email = "code".equals(kind);
        text(email ? "Code from email" : "Authenticator code", 20, fg, true);
        text(email ? "Bambu Lab has emailed you a one-time code." : "Enter the current code from your authenticator app (2FA).", 14, muted, false);
        final EditText code = field("Code", InputType.TYPE_CLASS_NUMBER);
        final TextView error = new TextView(this);
        final Button go = button("Verify", null);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Cloud.Session[] out = new Cloud.Session[1];
                background(go, error, new Work() {
                    public void run() throws Exception {
                        out[0] = "code".equals(kind) ? login.verifyCode(code.getText().toString()) : login.verifyTfa(code.getText().toString());
                    }
                }, new Runnable() {
                    public void run() { signedIn(out[0], login.email); showStatus(); }
                });
            }
        });
        button("Back", new View.OnClickListener() {
            @Override
            public void onClick(View v) { showLogin(null); }
        });
        error.setTextColor(0xFFDC3C3C);
        root.addView(error);
    }

    private void showToken() {
        root.removeAllViews();
        signingIn = true;
        text("Sign in with a token", 20, fg, true);
        text("Use this if you sign in to Bambu Lab with Google or Apple, or if the normal sign-in is blocked. "
                + "The token is the key your browser receives after signing in at bambulab.com. "
                + "It is easiest to get on a computer:", 14, muted, false);
        text("1. On a computer, open Chrome or Edge and sign in at bambulab.com (top right, Log in).\n"
                + "2. Press F12 to open the developer tools.\n"
                + "3. Open the Application tab (in Firefox: Storage). If you do not see it, click the » arrow next to the tabs.\n"
                + "4. In the left panel expand Cookies and click https://bambulab.com.\n"
                + "5. Find the row named token. Click it and copy the whole value shown at the bottom (a long text, often starting with \"ey\").\n"
                + "6. Send it to your phone in a way only you can read (for example a note or an email to yourself) and paste it below.",
                14, fg, false);
        text("Keep the token private: anyone who has it can use your Bambu account until it expires (about 3 months). "
                + "After that, repeat these steps.", 13, 0xFFE6A028, false);
        final EditText token = field("Paste the token here", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        final CheckBox china = new CheckBox(this);
        china.setText("China account (bambulab.cn)");
        china.setTextColor(muted);
        root.addView(china);
        final EditText name = field("Account name (optional, e.g. Work)", InputType.TYPE_CLASS_TEXT);
        final TextView error = new TextView(this);
        final Button go = button("Use token", null);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Cloud.Session[] out = new Cloud.Session[1];
                background(go, error, new Work() {
                    public void run() throws Exception {
                        out[0] = Cloud.fromToken(china.isChecked() ? "china" : "global", token.getText().toString(), 0);
                    }
                }, new Runnable() {
                    public void run() { signedIn(out[0], name.getText().toString()); showStatus(); }
                });
            }
        });
        button("Back", new View.OnClickListener() {
            @Override
            public void onClick(View v) { showLogin(null); }
        });
        error.setTextColor(0xFFDC3C3C);
        root.addView(error);
    }

    private void refreshNow() {
        Updater.runInBackground(getApplicationContext(), new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() { if (!signingIn && Store.signedIn(MainActivity.this)) showStatus(); }
                });
            }
        });
    }

    private void heading(String s) {
        TextView t = text(s, 16, fg, true);
        t.setPadding(0, dp(22), 0, dp(4));
    }

    private static final int[] INTERVALS = { 0, 2, 5, 15, 30, 60 };
    private static final String[] INTERVAL_NAMES = {
            "Only when I tap the widget", "Every 2 minutes", "Every 5 minutes",
            "Every 15 minutes (recommended)", "Every 30 minutes", "Every 60 minutes" };
    private static final String[] INTERVAL_HELP = {
            "No background activity at all, so no battery use. The widget shows the state from the last tap.",
            "Highest battery use. An alarm wakes the phone every 2 minutes; each update switches on the mobile radio "
                    + "and the processor for several seconds. Turn off battery optimization for this app (below), otherwise "
                    + "Android may skip or delay updates. While the phone is asleep, Android still allows such alarms only "
                    + "about every 10 minutes.",
            "Moderate battery use. Works like the 2 minute option, with fewer wake-ups. Turn off battery optimization "
                    + "for this app (below). While the phone is asleep, Android may stretch the interval to about 10 minutes.",
            "Low battery use. The shortest interval Android allows for battery friendly background work: Android "
                    + "groups the update with other apps' work, so the radio wakes up less often. Updates may come later "
                    + "while the phone is asleep. Battery optimization can stay on.",
            "Very low battery use. Same mechanism as 15 minutes, half as often.",
            "Minimal battery use. Same mechanism as 15 minutes, a quarter as often." };

    private void showStatus() {
        root.removeAllViews();
        signingIn = false;
        adding = false;
        text("Bambu Widget", 20, fg, true);

        JSONArray ps = Store.shownPrinters(this);
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p == null) continue;
            String extra = p.optLong("endsAt", 0) > 0
                    ? ", ends " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(p.optLong("endsAt"))) : "";
            TextView t = text(p.optString("name") + ": " + p.optString("label") + "  " + p.optInt("percent") + " %" + extra, 15, fg, false);
            if ("red".equals(p.optString("color"))) t.setTextColor(0xFFDC3C3C);
        }
        long at = Store.updatedAt(this);
        String msg = Store.message(this);
        text((Store.refreshing(this) ? "Updating…" : at > 0 ? "Updated " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(at)) : "")
                + (msg.length() > 0 ? "\n" + msg : ""), 13, muted, false);
        button("Update now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Store.setRefreshing(MainActivity.this, true);
                showStatus();
                refreshNow();
            }
        });
        text("Add the widget to your home screen: long-press an empty spot, choose Widgets, then Bambu Widget. "
                + "Tap the widget to update it now, tap \"Printers\" to open this app.", 13, muted, false);

        // ---- printers shown ----
        heading("Printers in the widget");
        text("Tick the printers to show in the widget and the notification; the arrows change their order.", 13, muted, false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        root.addView(box);
        fillPrinters(box);

        // ---- update frequency ----
        heading("Update frequency");
        text("Every update briefly connects to Bambu Cloud, reads the printers and disconnects. What costs battery "
                + "is waking the phone and the mobile radio: after each update the radio stays in a high power state for "
                + "a few seconds. Fewer updates mean less battery use.", 13, muted, false);
        final RadioGroup group = new RadioGroup(this);
        final TextView help = new TextView(this);
        int current = Store.interval(this);
        for (int i = 0; i < INTERVALS.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(1000 + i);
            rb.setText(INTERVAL_NAMES[i]);
            rb.setTextColor(fg);
            group.addView(rb);
            if (INTERVALS[i] == current) { rb.setChecked(true); help.setText(INTERVAL_HELP[i]); }
        }
        root.addView(group);
        help.setTextColor(0xFFE6A028);
        help.setTextSize(13);
        help.setPadding(0, dp(4), 0, 0);
        root.addView(help);
        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                int i = id - 1000;
                Store.setInterval(MainActivity.this, INTERVALS[i]);
                help.setText(INTERVAL_HELP[i]);
                Updater.schedule(MainActivity.this);
            }
        });

        // ---- status bar notification ----
        heading("Status bar notification");
        text("Shows a permanent notification with one line per printer (state, progress, end time), updated at the "
                + "frequency above. It does not use extra battery by itself, it only shows what the updates fetch. "
                + "On Android 14 and newer it can be swiped away; it comes back with the next update.", 13, muted, false);
        final Switch sw = new Switch(this);
        sw.setText("Show permanent notification");
        sw.setTextColor(fg);
        sw.setChecked(Store.notification(this));
        root.addView(sw);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Store.setNotification(MainActivity.this, on);
                if (on && Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 1);
                }
                Notifier.update(MainActivity.this);
            }
        });

        // ---- battery ----
        heading("Battery optimization");
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean exempt = pm.isIgnoringBatteryOptimizations(getPackageName());
        text(exempt
                ? "Battery optimization is OFF for this app: Android lets it update in the background as scheduled."
                : "Battery optimization is ON for this app. That is fine for 15 minutes and more. For 2 or 5 minute "
                        + "updates turn it off, otherwise Android may delay or skip them while the screen is off.",
                13, exempt ? 0xFF51A34F : muted, false);
        if (!exempt) {
            button("Turn off battery optimization for this app", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        startActivity(new Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"));
                    }
                }
            });
        }
        text("Some phones (Samsung, Xiaomi, Huawei, OnePlus and others) have their own extra battery savers that stop "
                + "background apps. If updates stop, also allow this app there: Settings › Apps › Bambu Widget › Battery "
                + "› Unrestricted (names differ by brand). Step by step guides per brand: dontkillmyapp.com", 13, muted, false);

        // ---- appearance ----
        heading("Appearance");
        text("Colors of this app and of the widget. The status bar notification always follows the phone's setting.", 13, muted, false);
        final String[] themes = { "system", "dark", "light" };
        final String[] themeNames = { "System", "Dark", "Light" };
        RadioGroup tg = new RadioGroup(this);
        tg.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < themes.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(2000 + i);
            rb.setText(themeNames[i]);
            rb.setTextColor(fg);
            tg.addView(rb);
            if (themes[i].equals(Store.theme(this))) rb.setChecked(true);
        }
        root.addView(tg);
        tg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                Store.setTheme(MainActivity.this, themes[id - 2000]);
                WidgetProvider.updateAll(MainActivity.this);
                recreate();
            }
        });

        // ---- accounts ----
        heading("Accounts");
        text("Printers of all accounts are shown together. Add an account to also see printers bound to another "
                + "Bambu Lab account (for example a work account).", 13, muted, false);
        final List<Cloud.Session> accounts = Store.accounts(this);
        JSONArray all = Store.printers(this);
        for (final Cloud.Session a : accounts) {
            int count = 0;
            for (int i = 0; i < all.length(); i++) if (a.id.equals(all.optJSONObject(i).optString("accountId"))) count++;
            text(a.label, 15, fg, true).setPadding(0, dp(10), 0, 0);
            TextView sub = text(("china".equals(a.region) ? "China" : "Global") + " \u00B7 "
                    + (a.expired ? "sign-in expired" : count + (count == 1 ? " printer" : " printers")), 13, a.expired ? 0xFFDC3C3C : muted, false);
            sub.setPadding(0, 0, 0, 0);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(row);
            if (a.expired) {
                row.addView(smallButton("Sign in again", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { adding = true; showLogin(null); }
                }));
            }
            row.addView(smallButton("Remove", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setMessage("Remove " + a.label + " and its printers from Bambu Widget?")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    Store.removeAccount(MainActivity.this, a.id);
                                    changedAccounts();
                                }
                            }).show();
                }
            }));
        }
        button("Add account", new View.OnClickListener() {
            @Override
            public void onClick(View v) { adding = true; showLogin(null); }
        });
        button(accounts.size() > 1 ? "Sign out of all accounts" : "Sign out", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Store.signOut(MainActivity.this);
                changedAccounts();
            }
        });
    }

    /** After removing accounts: reschedule, redraw the widget and notification, show the right screen. */
    private void changedAccounts() {
        Updater.schedule(this);
        WidgetProvider.updateAll(this);
        if (Store.signedIn(this)) { Notifier.update(this); showStatus(); }
        else { Notifier.cancel(this); showLogin(null); }
    }

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setTextColor(0xFF388BFD);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    /** One row per printer: show / hide check box and up / down arrows. */
    private void fillPrinters(final LinearLayout box) {
        box.removeAllViews();
        final JSONArray all = Store.printers(this);
        Set<String> hidden = Store.hidden(this);
        boolean multi = Store.accounts(this).size() > 1;
        if (all.length() == 0) {
            TextView t = new TextView(this);
            t.setText("No printers loaded yet. Tap Update now.");
            t.setTextColor(muted);
            box.addView(t);
            return;
        }
        for (int i = 0; i < all.length(); i++) {
            final JSONObject p = all.optJSONObject(i);
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            CheckBox cb = new CheckBox(this);
            String sub = p.optString("model");
            if (multi && p.optString("account").length() > 0) sub += (sub.length() > 0 ? " \u00B7 " : "") + p.optString("account");
            cb.setText(p.optString("name") + (sub.length() > 0 ? "\n" + sub : ""));
            cb.setTextColor(fg);
            cb.setChecked(!hidden.contains(p.optString("serial")));
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton b, boolean on) {
                    Store.setHidden(MainActivity.this, p.optString("serial"), !on);
                    printersChanged(box);
                }
            });
            row.addView(cb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(arrow("\u25B2", i > 0, new View.OnClickListener() {
                @Override
                public void onClick(View v) { move(all, index, -1, box); }
            }));
            row.addView(arrow("\u25BC", i < all.length() - 1, new View.OnClickListener() {
                @Override
                public void onClick(View v) { move(all, index, 1, box); }
            }));
            box.addView(row);
        }
    }

    private Button arrow(String label, boolean enabled, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setTextColor(enabled ? fg : (muted & 0x00FFFFFF) | 0x40000000);
        b.setEnabled(enabled);
        b.setMinWidth(0);
        b.setMinimumWidth(dp(44));
        b.setOnClickListener(l);
        return b;
    }

    private void move(JSONArray all, int index, int delta, LinearLayout box) {
        List<String> order = new ArrayList<String>();
        for (int i = 0; i < all.length(); i++) order.add(all.optJSONObject(i).optString("serial"));
        int j = index + delta;
        if (j < 0 || j >= order.size()) return;
        String s = order.get(index);
        order.set(index, order.get(j));
        order.set(j, s);
        Store.setOrder(this, order);
        printersChanged(box);
    }

    private void printersChanged(LinearLayout box) {
        fillPrinters(box);
        WidgetProvider.updateAll(this);
        Notifier.update(this);
    }
}
