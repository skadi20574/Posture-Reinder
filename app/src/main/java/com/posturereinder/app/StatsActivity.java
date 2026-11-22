package com.posturereinder.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class StatsActivity extends Activity {

    private StatsDb db;
    private int range = 0; // 0 week, 1 month, 2 year
    private ChartView chart;
    private TextView summary;
    private LinearLayout recent;
    private Button btnWeek, btnMonth, btnYear;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);
        db = new StatsDb(this);
        chart = findViewById(R.id.chart);
        summary = findViewById(R.id.summary);
        recent = findViewById(R.id.recent);
        btnWeek = findViewById(R.id.btnWeek);
        btnMonth = findViewById(R.id.btnMonth);
        btnYear = findViewById(R.id.btnYear);

        btnWeek.setOnClickListener(v -> { range = 0; reload(); });
        btnMonth.setOnClickListener(v -> { range = 1; reload(); });
        btnYear.setOnClickListener(v -> { range = 2; reload(); });
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            db.clear();
            reload();
        });
        findViewById(R.id.btnExport).setOnClickListener(v -> exportData());
        findViewById(R.id.btnImport).setOnClickListener(v -> importData());
        reload();
    }

    // ------------------------------------------------------------------ export / import

    private static final int REQ_EXPORT = 11;
    private static final int REQ_IMPORT = 12;

    private void exportData() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "posture-stats-" +
                new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date()) + ".json");
        startActivityForResult(i, REQ_EXPORT);
    }

    private void importData() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == REQ_EXPORT) {
                String json = toJson(db.all());
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os != null) os.write(json.getBytes("UTF-8"));
                }
                Toast.makeText(this, "已导出", Toast.LENGTH_SHORT).show();
            } else if (requestCode == REQ_IMPORT) {
                StringBuilder sb = new StringBuilder();
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is != null) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                    }
                }
                int added = fromJson(sb.toString());
                Toast.makeText(this, "已导入 " + added + " 条", Toast.LENGTH_SHORT).show();
                reload();
            }
        } catch (Exception e) {
            Toast.makeText(this, "失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String toJson(List<StatsDb.Row> rows) throws JSONException {
        JSONArray arr = new JSONArray();
        for (StatsDb.Row r : rows) {
            JSONObject o = new JSONObject();
            o.put("start", r.start);
            o.put("end", r.end);
            o.put("good", r.good);
            o.put("bad", r.bad);
            o.put("fwd", r.fwd);
            o.put("back", r.back);
            o.put("closed", r.closed);
            o.put("distracted", r.distracted);
            o.put("away", r.away);
            o.put("occluded", r.occluded);
            o.put("fwdN", r.fwdN);
            o.put("backN", r.backN);
            o.put("closedN", r.closedN);
            o.put("distractedN", r.distractedN);
            o.put("alerts", r.alerts);
            o.put("screenOn", r.screenOn);
            o.put("score", r.score);
            arr.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("app", "posturereinder");
        root.put("version", 1);
        root.put("sessions", arr);
        return root.toString(2);
    }

    private int fromJson(String text) throws JSONException {
        JSONObject root = new JSONObject(text);
        JSONArray arr = root.optJSONArray("sessions");
        if (arr == null) return 0;
        int added = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            StatsDb.Row r = new StatsDb.Row();
            r.start = o.optLong("start");
            r.end = o.optLong("end");
            r.good = o.optInt("good");
            r.bad = o.optInt("bad");
            r.fwd = o.optInt("fwd");
            r.back = o.optInt("back");
            r.closed = o.optInt("closed");
            r.distracted = o.optInt("distracted");
            r.away = o.optInt("away");
            r.occluded = o.optInt("occluded");
            r.fwdN = o.optInt("fwdN");
            r.backN = o.optInt("backN");
            r.closedN = o.optInt("closedN");
            r.distractedN = o.optInt("distractedN");
            r.alerts = o.optInt("alerts");
            r.screenOn = o.optInt("screenOn");
            r.score = o.optInt("score");
            if (r.start > 0) {
                db.insertRaw(r);
                added++;
            }
        }
        return added;
    }

    private void reload() {
        long now = System.currentTimeMillis();
        long from;
        List<StatsDb.Bucket> buckets;
        if (range == 2) {
            buckets = db.monthly(12);
            Calendar c = Calendar.getInstance();
            c.add(Calendar.MONTH, -12);
            from = c.getTimeInMillis();
        } else {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            c.add(Calendar.DAY_OF_MONTH, range == 0 ? -6 : -29);
            from = c.getTimeInMillis();
            buckets = db.daily(from, now);
        }

        float[] values = new float[buckets.size()];
        String[] labels = new String[buckets.size()];
        for (int i = 0; i < buckets.size(); i++) {
            values[i] = buckets.get(i).sessions > 0 ? buckets.get(i).avgScore : 0;
            labels[i] = buckets.get(i).label;
        }
        chart.setData(values, labels);

        StatsDb.Row t = db.totals(from, now);
        double active = t.good + t.bad + t.fwd;
        double goodPct = active > 0 ? 100.0 * t.good / active : 0;
        summary.setText(String.format(Locale.CHINA,
                "共 %d 次监控 · 有效时长 %s · 平均分 %d\n端正率 %.0f%% · 提醒 %d 次 · 亮屏 %d 次\n" +
                        "前倾 %s（%d 次）· 后仰 %s（%d 次）\n闭眼 %s（%d 次）· 分心 %s（%d 次）\n离开 %s · 遮挡 %s",
                t.id, dur(t.good + t.bad + t.fwd + t.back), t.score, goodPct,
                t.alerts, t.screenOn,
                dur(t.fwd), t.fwdN, dur(t.back), t.backN,
                dur(t.closed), t.closedN, dur(t.distracted), t.distractedN,
                dur(t.away), dur(t.occluded)));

        btnWeek.setTextColor(range == 0 ? getColor(R.color.accent) : getColor(R.color.fg3));
        btnMonth.setTextColor(range == 1 ? getColor(R.color.accent) : getColor(R.color.fg3));
        btnYear.setTextColor(range == 2 ? getColor(R.color.accent) : getColor(R.color.fg3));

        renderRecent();
    }

    private void renderRecent() {
        recent.removeAllViews();
        List<StatsDb.Row> rows = db.recent(8);
        if (rows.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("还没有记录");
            tv.setTextColor(getColor(R.color.fg3));
            tv.setTextSize(12);
            recent.addView(tv);
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (StatsDb.Row r : rows) {
            TextView tv = new TextView(this);
            String when = fmt.format(new Date(r.start));
            long mins = Math.max(1, (r.end - r.start) / 60000);
            tv.setText(String.format(Locale.CHINA,
                    "%s · %d 分钟 · %d 分 · 提醒 %d · 后仰 %s",
                    when, mins, r.score, r.alerts, dur(r.back)));
            tv.setTextColor(getColor(R.color.fg2));
            tv.setTextSize(12);
            tv.setPadding(0, 8, 0, 8);
            tv.setGravity(Gravity.START);
            recent.addView(tv);
        }
    }

    private static String dur(int ms) {
        int sec = Math.max(0, ms / 1000);
        int m = sec / 60;
        int s = sec % 60;
        if (m >= 60) return (m / 60) + "h" + (m % 60) + "m";
        return m + "分" + (s > 0 ? s + "秒" : "");
    }
}
