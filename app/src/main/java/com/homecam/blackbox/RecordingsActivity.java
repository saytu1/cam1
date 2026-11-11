package com.homecam.blackbox;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 녹화 파일 목록 (날짜별 묶음, 최신 순).
 * - 파일 터치: 재생 / 길게 누르기: 잠금·삭제 메뉴
 * - 날짜 제목 터치: 날짜 잠금·해당 날짜 삭제
 * - 전체 삭제: 잠긴 파일은 제외
 */
public class RecordingsActivity extends AppCompatActivity {
    private static final long RECENT_MS = 15_000; // 녹화 중인 파일 보호

    private LockStore locks;
    private final List<RecStore.Rec> all = new ArrayList<>();
    private final List<Object> rows = new ArrayList<>(); // String(날짜) 또는 Rec
    private final Map<String, List<RecStore.Rec>> byDate = new LinkedHashMap<>();
    private boolean lockedOnly;
    private Button tabAll, tabLocked;
    private TextView summary, empty;
    private RowAdapter adapter;
    private String currentDisplay;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        locks = new LockStore(this);
        int pad = dp(8);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(pad, pad, pad, 0);
        tabAll = new Button(this);
        tabLocked = new Button(this);
        Button delAll = new Button(this);
        delAll.setText("전체 삭제");
        delAll.setTextColor(0xFFE53935);
        tabAll.setOnClickListener(v -> { lockedOnly = false; rebuild(); });
        tabLocked.setOnClickListener(v -> { lockedOnly = true; rebuild(); });
        delAll.setOnClickListener(v -> confirmDeleteAll());
        LinearLayout.LayoutParams w = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        bar.addView(tabAll, w);
        bar.addView(tabLocked, new LinearLayout.LayoutParams(w));
        bar.addView(delAll, new LinearLayout.LayoutParams(w));
        root.addView(bar);

        summary = new TextView(this);
        summary.setPadding(dp(14), pad / 2, dp(14), pad / 2);
        summary.setTextSize(12);
        root.addView(summary);

        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        ListView list = new ListView(this);
        empty = new TextView(this);
        empty.setPadding(dp(20), dp(20), dp(20), dp(20));
        frame.addView(list);
        frame.addView(empty);
        list.setEmptyView(empty);
        root.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Object r = rows.get(pos);
            if (r instanceof RecStore.Rec) play((RecStore.Rec) r);
            else dateMenu((String) r);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            Object r = rows.get(pos);
            if (r instanceof RecStore.Rec) fileMenu((RecStore.Rec) r);
            else dateMenu((String) r);
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        all.clear();
        all.addAll(StorageHelper.listAllNewestFirst(this));
        rebuild();
    }

    private void rebuild() {
        byDate.clear();
        int lockedCount = 0;
        long lockedSize = 0;
        for (RecStore.Rec f : all) {
            boolean lk = locks.isLocked(f);
            if (lk) { lockedCount++; lockedSize += f.size; }
            if (lockedOnly && !lk) continue;
            String d = LockStore.dateOf(f.name);
            if (d == null) d = "기타";
            List<RecStore.Rec> l = byDate.get(d);
            if (l == null) byDate.put(d, l = new ArrayList<>());
            l.add(f);
        }
        rows.clear();
        for (Map.Entry<String, List<RecStore.Rec>> e : byDate.entrySet()) {
            rows.add(e.getKey());
            rows.addAll(e.getValue());
        }
        tabAll.setText("전체 " + all.size());
        tabLocked.setText("🔒 잠금 " + lockedCount);
        tabAll.setTypeface(null, lockedOnly ? Typeface.NORMAL : Typeface.BOLD);
        tabLocked.setTypeface(null, lockedOnly ? Typeface.BOLD : Typeface.NORMAL);
        RecStore cur = StorageHelper.currentStore(this);
        currentDisplay = cur.isSaf() ? null : cur.displayPath();
        summary.setText("저장 위치: " + cur.label(this) + " " + cur.displayPath()
                + "\n전체 " + StorageHelper.formatBytes(StorageHelper.totalSize(all))
                + " · 잠금 " + StorageHelper.formatBytes(lockedSize)
                + "  (잠긴 파일은 전체 삭제·자동 삭제되지 않음)");
        empty.setText(lockedOnly ? "잠긴 파일이 없습니다\n(파일을 길게 눌러 잠글 수 있습니다)" : "녹화 파일이 없습니다");
        setTitle(getString(R.string.recordings));
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------ 메뉴

    private void fileMenu(RecStore.Rec f) {
        boolean fileLocked = locks.isFileLocked(f);
        String date = LockStore.dateOf(f.name);
        boolean dateLocked = locks.isDateLocked(date);
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        labels.add("▶ 재생");
        acts.add(() -> play(f));
        labels.add(fileLocked ? "🔓 파일 잠금 해제" : "🔒 파일 잠금");
        acts.add(() -> { locks.setFileLocked(f, !fileLocked); rebuild(); });
        if (date != null) {
            labels.add(dateLocked ? "🔓 " + prettyDate(date) + " 날짜 잠금 해제" : "🔒 " + prettyDate(date) + " 날짜 전체 잠금");
            acts.add(() -> { locks.setDateLocked(date, !dateLocked); rebuild(); });
        }
        labels.add("🗑 삭제");
        acts.add(() -> {
            if (locks.isLocked(f)) {
                toast(dateLocked && !fileLocked
                        ? "날짜 잠금이 걸린 파일입니다. 날짜 잠금을 먼저 해제하세요"
                        : "잠긴 파일입니다. 잠금을 먼저 해제하세요");
                return;
            }
            new AlertDialog.Builder(this)
                    .setMessage(f.name + " 파일을 삭제할까요?")
                    .setPositiveButton("삭제", (d, w) -> {
                        if (StorageHelper.deleteRecording(this, f)) reload();
                        else toast("삭제하지 못했습니다");
                    })
                    .setNegativeButton("취소", null)
                    .show();
        });
        new AlertDialog.Builder(this)
                .setTitle(prettyName(f.name))
                .setItems(labels.toArray(new String[0]), (d, which) -> acts.get(which).run())
                .show();
    }

    private void dateMenu(String date) {
        if ("기타".equals(date)) return;
        boolean locked = locks.isDateLocked(date);
        String[] items = {
                locked ? "🔓 날짜 잠금 해제" : "🔒 이 날짜 전체 잠금 (이후 녹화분 포함)",
                "🗑 이 날짜 파일 삭제 (잠금 제외)"
        };
        new AlertDialog.Builder(this)
                .setTitle(prettyDate(date))
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        locks.setDateLocked(date, !locked);
                        rebuild();
                    } else {
                        List<RecStore.Rec> target = new ArrayList<>();
                        for (RecStore.Rec f : all) if (date.equals(LockStore.dateOf(f.name))) target.add(f);
                        confirmDelete(target, prettyDate(date) + " 파일");
                    }
                })
                .show();
    }

    private void confirmDeleteAll() {
        confirmDelete(new ArrayList<>(all), "전체 파일");
    }

    /** 잠긴 파일과 녹화 중인 파일을 제외하고 삭제 */
    private void confirmDelete(List<RecStore.Rec> target, String what) {
        List<RecStore.Rec> del = new ArrayList<>();
        int skipped = 0;
        long now = System.currentTimeMillis();
        for (RecStore.Rec f : target) {
            if (locks.isLocked(f)) skipped++;
            else if (now - f.lastModified < RECENT_MS || isRecordingNow(f)) skipped++; // 지금 녹화 중인 파일
            else del.add(f);
        }
        if (del.isEmpty()) {
            toast("삭제할 수 있는 파일이 없습니다" + (skipped > 0 ? " (잠금/녹화 중 " + skipped + "개 보호됨)" : ""));
            return;
        }
        long size = StorageHelper.totalSize(del);
        new AlertDialog.Builder(this)
                .setTitle(what + " 삭제")
                .setMessage(del.size() + "개 파일(" + StorageHelper.formatBytes(size) + ")을 삭제합니다."
                        + (skipped > 0 ? "\n잠긴 파일·녹화 중인 파일 " + skipped + "개는 삭제되지 않습니다." : "")
                        + "\n\n되돌릴 수 없습니다. 계속할까요?")
                .setPositiveButton("삭제", (d, w) -> deleteInBackground(del))
                .setNegativeButton("취소", null)
                .show();
    }

    private void deleteInBackground(List<RecStore.Rec> del) {
        AlertDialog progress = new AlertDialog.Builder(this)
                .setMessage("삭제 중…")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            int ok = 0;
            for (RecStore.Rec f : del) if (StorageHelper.deleteRecording(this, f)) ok++;
            final int n = ok;
            ui.post(() -> {
                progress.dismiss();
                toast(n + "개 파일을 삭제했습니다");
                reload();
            });
        }).start();
    }

    // ------------------------------------------------------------ 표시

    /** 20261108 → 2026-11-08 */
    private static String prettyDate(String d) {
        if (d == null || d.length() != 8) return String.valueOf(d);
        return d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8);
    }

    /** BB_20261108_153000.mp4 → 2026-11-08 15:30:00 */
    private static String prettyName(String name) {
        try {
            String s = name.substring(StorageHelper.PREFIX.length(), name.length() - 4);
            return prettyDate(s.substring(0, 8)) + " " + s.substring(9, 11) + ":" + s.substring(11, 13) + ":" + s.substring(13, 15);
        } catch (Exception e) {
            return name;
        }
    }

    private static String timeOf(String name) {
        String p = prettyName(name);
        return p.length() > 11 ? p.substring(11) : p;
    }

    /** 가장 최근 파일이 방금까지 쓰이고 있으면 녹화 중으로 간주 (SAF 는 수정 시각이 늦게 갱신될 수 있음) */
    private boolean isRecordingNow(RecStore.Rec f) {
        if (all.isEmpty() || all.get(0) != f) return false;
        String n = f.name;
        try {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US);
            long start = fmt.parse(n.substring(StorageHelper.PREFIX.length(), n.length() - 4)).getTime();
            long segMs = new Prefs(this).segmentMinutes() * 60_000L;
            return System.currentTimeMillis() - start < segMs + 60_000;
        } catch (Exception e) {
            return false;
        }
    }

    private void play(RecStore.Rec f) {
        try {
            Uri uri = f.uri != null ? f.uri : FileProvider.getUriForFile(this, getPackageName() + ".files", f.file);
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            toast("재생할 수 있는 앱이 없습니다: " + e.getMessage());
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return rows.get(i) instanceof RecStore.Rec ? 1 : 0; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            Object r = rows.get(pos);
            if (r instanceof String) {
                TextView tv = convertView instanceof TextView ? (TextView) convertView : new TextView(RecordingsActivity.this);
                String d = (String) r;
                List<RecStore.Rec> l = byDate.get(d);
                tv.setPadding(dp(14), dp(10), dp(14), dp(6));
                tv.setBackgroundColor(0xFF263238);
                tv.setTextColor(Color.WHITE);
                tv.setTypeface(null, Typeface.BOLD);
                tv.setText((locks.isDateLocked(d) ? "🔒 " : "📅 ") + prettyDate(d)
                        + "   " + (l != null ? l.size() : 0) + "개 · "
                        + StorageHelper.formatBytes(l != null ? StorageHelper.totalSize(l) : 0)
                        + (locks.isDateLocked(d) ? "  (날짜 잠금)" : ""));
                return tv;
            }
            RecStore.Rec f = (RecStore.Rec) r;
            View v = convertView != null && !(convertView instanceof TextView) ? convertView
                    : getLayoutInflater().inflate(android.R.layout.simple_list_item_2, parent, false);
            TextView t1 = v.findViewById(android.R.id.text1);
            TextView t2 = v.findViewById(android.R.id.text2);
            boolean fl = locks.isFileLocked(f);
            boolean dl = locks.isDateLocked(LockStore.dateOf(f.name));
            t1.setText((fl || dl ? "🔒 " : "🎬 ") + timeOf(f.name));
            String info = StorageHelper.formatBytes(f.size);
            if (fl) info += " · 파일 잠금";
            else if (dl) info += " · 날짜 잠금";
            if (f.file != null && currentDisplay != null && !currentDisplay.equals(f.file.getParent())) {
                info += " · " + f.file.getParent();
            }
            t2.setText(info);
            return v;
        }
    }
}
