package com.one.phonemanager;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Size;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@CapacitorPlugin(
    name = "PhoneManager",
    permissions = {
        @Permission(alias = "images", strings = { Manifest.permission.READ_MEDIA_IMAGES }),
        @Permission(alias = "videos", strings = { Manifest.permission.READ_MEDIA_VIDEO }),
        @Permission(alias = "audio", strings = { Manifest.permission.READ_MEDIA_AUDIO }),
        @Permission(alias = "storage", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
    }
)
public class PhoneManagerPlugin extends Plugin {

    private boolean torch = false;

    // ---------- Installed apps ----------
    @PluginMethod
    public void getInstalledApps(PluginCall call) {
        PackageManager pm = getContext().getPackageManager();
        Map<String, Long> used = usage();
        JSArray out = new JSArray();
        for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            Intent li = pm.getLaunchIntentForPackage(ai.packageName);
            if (li == null || ai.packageName.equals(getContext().getPackageName())) continue;
            boolean game = (Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME)
                    || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
            JSObject o = new JSObject();
            o.put("packageName", ai.packageName);
            o.put("appName", pm.getApplicationLabel(ai).toString());
            o.put("isGame", game);
            o.put("size", new File(ai.sourceDir).length());
            try { o.put("updated", pm.getPackageInfo(ai.packageName, 0).lastUpdateTime); } catch (Exception e) { o.put("updated", 0); }
            Long u = used.get(ai.packageName);
            o.put("lastUsed", u == null ? 0L : u);
            out.put(o);
        }
        JSObject r = new JSObject();
        r.put("apps", out);
        call.resolve(r);
    }

    private Map<String, Long> usage() {
        Map<String, Long> m = new HashMap<>();
        try {
            AppOpsManager ao = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
            int mode = ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getContext().getPackageName());
            if (mode != AppOpsManager.MODE_ALLOWED) return m;
            UsageStatsManager us = (UsageStatsManager) getContext().getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            for (Map.Entry<String, UsageStats> e : us.queryAndAggregateUsageStats(now - 30L * 86400000L, now).entrySet()) {
                m.put(e.getKey(), e.getValue().getLastTimeUsed());
            }
        } catch (Exception ignored) { }
        return m;
    }

    @PluginMethod
    public void getAppIcon(PluginCall call) {
        try {
            Drawable d = getContext().getPackageManager().getApplicationIcon(call.getString("packageName"));
            Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 96, 96);
            d.draw(new Canvas(b));
            JSObject r = new JSObject();
            r.put("data", b64(b, Bitmap.CompressFormat.PNG, 100));
            b.recycle();
            call.resolve(r);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void launchApp(PluginCall call) {
        Intent i = getContext().getPackageManager().getLaunchIntentForPackage(call.getString("packageName", ""));
        if (i == null) { call.reject("App not found"); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    // ---------- Media (MediaStore) ----------
    private String alias(String type) {
        if (Build.VERSION.SDK_INT >= 33) return "pdfs".equals(type) ? null : type;
        return "storage";
    }

    @PluginMethod
    public void getMedia(PluginCall call) {
        String type = call.getString("type", "images");
        String a = alias(type);
        if (a != null && getPermissionState(a) != PermissionState.GRANTED) {
            requestPermissionForAlias(a, call, "mediaPerm");
            return;
        }
        query(call, type);
    }

    @PermissionCallback
    private void mediaPerm(PluginCall call) {
        String type = call.getString("type", "images");
        if (getPermissionState(alias(type)) == PermissionState.GRANTED) query(call, type);
        else call.reject("Permission denied");
    }

    private void query(PluginCall call, String type) {
        Uri base;
        String sel = null;
        String[] args = null;
        String dur = null;
        switch (type) {
            case "videos":
                base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                dur = MediaStore.Video.Media.DURATION;
                break;
            case "audio":
                base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                dur = MediaStore.Audio.Media.DURATION;
                sel = MediaStore.Audio.Media.IS_MUSIC + "!=0";
                break;
            case "pdfs":
                base = MediaStore.Files.getContentUri("external");
                sel = MediaStore.Files.FileColumns.MIME_TYPE + "=?";
                args = new String[] { "application/pdf" };
                break;
            default:
                base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        }
        ArrayList<String> p = new ArrayList<>(Arrays.asList(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATA));
        if (dur != null) p.add(dur);
        if (type.equals("audio")) p.add(MediaStore.Audio.Media.ARTIST);
        int limit = call.getInt("limit", 600);
        JSArray out = new JSArray();
        try (Cursor c = getContext().getContentResolver().query(base, p.toArray(new String[0]), sel, args,
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            while (c != null && c.moveToNext() && out.length() < limit) {
                long id = c.getLong(0);
                JSObject o = new JSObject();
                o.put("id", id);
                o.put("uri", ContentUris.withAppendedId(base, id).toString());
                o.put("name", c.getString(1));
                o.put("size", c.getLong(2));
                o.put("date", c.getLong(3) * 1000L);
                o.put("mime", c.getString(4));
                o.put("path", c.getString(5));
                int i = 6;
                if (dur != null) o.put("duration", c.getLong(i++));
                if (type.equals("audio")) o.put("artist", c.getString(i));
                out.put(o);
            }
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
            return;
        }
        JSObject r = new JSObject();
        r.put("items", out);
        call.resolve(r);
    }

    @PluginMethod
    public void getThumbnail(PluginCall call) {
        try {
            Uri u = Uri.parse(call.getString("uri"));
            Bitmap b;
            if (Build.VERSION.SDK_INT >= 29) {
                b = getContext().getContentResolver().loadThumbnail(u, new Size(256, 256), null);
            } else {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 8;
                try (InputStream s = getContext().getContentResolver().openInputStream(u)) {
                    b = BitmapFactory.decodeStream(s, null, o);
                }
            }
            JSObject r = new JSObject();
            r.put("data", b64(b, Bitmap.CompressFormat.JPEG, 75));
            call.resolve(r);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void openFile(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(call.getString("uri")), call.getString("mime"));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try { getContext().startActivity(i); call.resolve(); }
        catch (Exception e) { call.reject("No app can open this file"); }
    }

    // ---------- Device stats ----------
    @PluginMethod
    public void getDeviceStats(PluginCall call) {
        Context ctx = getContext();
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        Intent bi = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int st = bi == null ? -1 : bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        JSObject r = new JSObject();
        r.put("ramTotal", mi.totalMem);
        r.put("ramAvail", mi.availMem);
        r.put("storageTotal", sf.getTotalBytes());
        r.put("storageFree", sf.getAvailableBytes());
        r.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
        r.put("charging", st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL);
        r.put("temp", bi == null ? 0.0 : bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
        call.resolve(r);
    }

    // ---------- Boost / system shortcuts ----------
    @PluginMethod
    public void boostMemory(PluginCall call) {
        ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        int n = 0;
        for (ApplicationInfo ai : getContext().getPackageManager().getInstalledApplications(0)) {
            if (ai.packageName.equals(getContext().getPackageName()) || (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            am.killBackgroundProcesses(ai.packageName);
            n++;
        }
        JSObject r = new JSObject();
        r.put("count", n);
        call.resolve(r);
    }

    @PluginMethod
    public void toggleFlashlight(PluginCall call) {
        try {
            CameraManager cm = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            torch = !torch;
            cm.setTorchMode(cm.getCameraIdList()[0], torch);
            JSObject r = new JSObject();
            r.put("on", torch);
            call.resolve(r);
        } catch (Exception e) {
            torch = !torch;
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        String t = call.getString("panel", "");
        String pkg = "package:" + getContext().getPackageName();
        Intent i;
        if (t.equals("wifi")) i = new Intent(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY : Settings.ACTION_WIFI_SETTINGS);
        else if (t.equals("usage")) i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        else if (t.equals("overlay")) i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse(pkg));
        else if (t.equals("files") && Build.VERSION.SDK_INT >= 30) i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse(pkg));
        else i = new Intent(Settings.ACTION_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    private String b64(Bitmap b, Bitmap.CompressFormat f, int q) {
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        b.compress(f, q, s);
        return Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP);
    }
}
