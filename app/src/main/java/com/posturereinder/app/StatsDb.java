package com.posturereinder.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Session history: one row per monitoring run. */
public class StatsDb extends SQLiteOpenHelper {

    public static final class Row {
        public long id;
        public long start;
        public long end;
        public int good, bad, fwd, back, closed, distracted, away, occluded;
        public int fwdN, backN, closedN, distractedN;
        public int alerts, screenOn, score;
    }

    public static final class Bucket {
        public String label;
        public long from;
        public int monitoredMs;
        public int goodMs;
        public int alerts;
        public int sessions;
        public float avgScore;
    }

    public StatsDb(Context c) {
        super(c, "posture_stats.db", null, 2);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "start INTEGER NOT NULL," +
                "end INTEGER NOT NULL," +
                "good INTEGER NOT NULL," +
                "bad INTEGER NOT NULL," +
                "fwd INTEGER NOT NULL," +
                "back INTEGER NOT NULL," +
                "closed INTEGER NOT NULL," +
                "distracted INTEGER NOT NULL," +
                "away INTEGER NOT NULL," +
                "occluded INTEGER NOT NULL," +
                "fwd_n INTEGER NOT NULL," +
                "back_n INTEGER NOT NULL," +
                "closed_n INTEGER NOT NULL," +
                "distracted_n INTEGER NOT NULL," +
                "alerts INTEGER NOT NULL," +
                "screen_on INTEGER NOT NULL," +
                "score INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS sessions");
        onCreate(db);
    }

    public long insert(Row r) {
        ContentValues v = new ContentValues();
        v.put("start", r.start);
        v.put("end", r.end);
        v.put("good", r.good);
        v.put("bad", r.bad);
        v.put("fwd", r.fwd);
        v.put("back", r.back);
        v.put("closed", r.closed);
        v.put("distracted", r.distracted);
        v.put("away", r.away);
        v.put("occluded", r.occluded);
        v.put("fwd_n", r.fwdN);
        v.put("back_n", r.backN);
        v.put("closed_n", r.closedN);
        v.put("distracted_n", r.distractedN);
        v.put("alerts", r.alerts);
        v.put("screen_on", r.screenOn);
        v.put("score", r.score);
        return getWritableDatabase().insert("sessions", null, v);
    }

    public List<Row> recent(int limit) {
        List<Row> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("sessions", null, null, null,
                null, null, "start DESC", String.valueOf(limit))) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }

    public List<Row> all() {
        List<Row> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("sessions", null, null, null,
                null, null, "start ASC", null)) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }

    public long insertRaw(Row r) {
        return insert(r);
    }

    public Row totals(long from, long to) {
        Row t = new Row();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*), SUM(good), SUM(bad), SUM(fwd), SUM(back), SUM(closed)," +
                        " SUM(distracted), SUM(away), SUM(occluded), SUM(alerts), SUM(screen_on)," +
                        " AVG(score), SUM(fwd_n), SUM(back_n), SUM(closed_n), SUM(distracted_n)" +
                        " FROM sessions WHERE start >= ? AND start < ?",
                new String[]{String.valueOf(from), String.valueOf(to)})) {
            if (c.moveToFirst()) {
                t.id = c.getLong(0);
                t.good = c.getInt(1);
                t.bad = c.getInt(2);
                t.fwd = c.getInt(3);
                t.back = c.getInt(4);
                t.closed = c.getInt(5);
                t.distracted = c.getInt(6);
                t.away = c.getInt(7);
                t.occluded = c.getInt(8);
                t.alerts = c.getInt(9);
                t.screenOn = c.getInt(10);
                t.score = Math.round(c.getFloat(11));
                t.fwdN = c.getInt(12);
                t.backN = c.getInt(13);
                t.closedN = c.getInt(14);
                t.distractedN = c.getInt(15);
            }
        }
        return t;
    }

    /** Daily buckets between from and to (ms), inclusive of the day containing from. */
    public List<Bucket> daily(long from, long to) {
        List<Bucket> out = new ArrayList<>();
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(from);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        while (day.getTimeInMillis() <= to) {
            long s = day.getTimeInMillis();
            day.add(Calendar.DAY_OF_MONTH, 1);
            long e = day.getTimeInMillis();
            Row r = totals(s, e);
            Bucket b = new Bucket();
            b.from = s;
            b.sessions = (int) r.id;
            b.goodMs = r.good;
            b.monitoredMs = r.good + r.bad + r.fwd + r.back;
            b.alerts = r.alerts;
            b.avgScore = r.score;
            b.label = String.format(java.util.Locale.CHINA, "%02d/%02d",
                    day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH));
            if (b.label.startsWith("0")) b.label = b.label.substring(1);
            out.add(b);
        }
        return out;
    }

    /** Monthly buckets for the last 12 months. */
    public List<Bucket> monthly(int months) {
        List<Bucket> out = new ArrayList<>();
        Calendar m = Calendar.getInstance();
        m.set(Calendar.DAY_OF_MONTH, 1);
        m.set(Calendar.HOUR_OF_DAY, 0);
        m.set(Calendar.MINUTE, 0);
        m.set(Calendar.SECOND, 0);
        m.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < months; i++) {
            long e = m.getTimeInMillis();
            m.add(Calendar.MONTH, -1);
            long s = m.getTimeInMillis();
            Row r = totals(s, e);
            Bucket b = new Bucket();
            b.from = s;
            b.sessions = (int) r.id;
            b.goodMs = r.good;
            b.monitoredMs = r.good + r.bad + r.fwd + r.back;
            b.alerts = r.alerts;
            b.avgScore = r.score;
            b.label = (m.get(Calendar.MONTH) + 1) + "月";
            out.add(0, b);
        }
        return out;
    }

    public void clear() {
        getWritableDatabase().delete("sessions", null, null);
    }

    private static Row read(Cursor c) {
        Row r = new Row();
        r.id = c.getLong(c.getColumnIndexOrThrow("id"));
        r.start = c.getLong(c.getColumnIndexOrThrow("start"));
        r.end = c.getLong(c.getColumnIndexOrThrow("end"));
        r.good = c.getInt(c.getColumnIndexOrThrow("good"));
        r.bad = c.getInt(c.getColumnIndexOrThrow("bad"));
        r.fwd = c.getInt(c.getColumnIndexOrThrow("fwd"));
        r.back = c.getInt(c.getColumnIndexOrThrow("back"));
        r.closed = c.getInt(c.getColumnIndexOrThrow("closed"));
        r.distracted = c.getInt(c.getColumnIndexOrThrow("distracted"));
        r.away = c.getInt(c.getColumnIndexOrThrow("away"));
        r.occluded = c.getInt(c.getColumnIndexOrThrow("occluded"));
        r.alerts = c.getInt(c.getColumnIndexOrThrow("alerts"));
        r.screenOn = c.getInt(c.getColumnIndexOrThrow("screen_on"));
        r.score = c.getInt(c.getColumnIndexOrThrow("score"));
        return r;
    }
}
