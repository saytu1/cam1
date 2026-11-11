package com.homecam.blackbox;

import android.app.TimePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/** 예약 녹화: 요일 + 시작~종료 시각 구간을 여러 개 등록 */
public class ScheduleActivity extends AppCompatActivity {
    private Prefs prefs;
    private final List<Schedule> items = new ArrayList<>();
    private ArrayAdapter<Schedule> adapter;
    private SwitchCompat master;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("예약 녹화");
        prefs = new Prefs(this);
        int pad = dp(14);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        master = new SwitchCompat(this);
        master.setText("예약 녹화 사용");
        master.setTextSize(18);
        master.setChecked(prefs.scheduleEnabled());
        master.setOnCheckedChangeListener((b, on) -> {
            prefs.setScheduleEnabled(on);
            if (on && items.isEmpty()) {
                items.add(new Schedule());
                save();
            }
            if (on) {
                // 예약을 켜면 녹화 기능도 켜서 예약 시간에 자동 동작하도록 함
                prefs.setWantRecording(true);
                RecorderService.start(this);
            }
            applyChanges();
        });
        root.addView(master);

        TextView help = new TextView(this);
        help.setText("켜면 아래 요일·시간대에만 자동으로 녹화를 시작하고 종료합니다. 매주 반복됩니다.\n"
                + "끄면 항상 녹화합니다. 종료 시각이 시작보다 이르면 다음 날까지 녹화합니다.\n"
                + "항목을 누르면 수정, 길게 누르면 삭제합니다.");
        help.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(help);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, 0, 0, pad / 2);
        status.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark));
        root.addView(status);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<Schedule>(this, android.R.layout.simple_list_item_2, android.R.id.text1, items) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                Schedule s = getItem(position);
                TextView t1 = v.findViewById(android.R.id.text1);
                TextView t2 = v.findViewById(android.R.id.text2);
                t1.setText((s.enabled ? "⏰ " : "⏸ ") + s.timeText());
                t2.setText(s.daysText() + (s.enabled ? "" : "  (사용 안 함)"));
                v.setAlpha(s.enabled ? 1f : 0.5f);
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> edit(pos));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            new AlertDialog.Builder(this)
                    .setMessage("이 예약을 삭제할까요?\n" + items.get(pos).daysText() + " " + items.get(pos).timeText())
                    .setPositiveButton("삭제", (d, w) -> {
                        items.remove(pos);
                        save();
                        applyChanges();
                    })
                    .setNegativeButton("취소", null)
                    .show();
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Button add = new Button(this);
        add.setText("+ 예약 추가");
        add.setOnClickListener(v -> edit(-1));
        root.addView(add);

        setContentView(root);
        items.addAll(prefs.schedules());
        adapter.notifyDataSetChanged();
        updateStatus();
    }

    private void save() {
        prefs.setSchedules(items);
        adapter.notifyDataSetChanged();
    }

    /** 변경 사항을 서비스와 알람에 반영 */
    private void applyChanges() {
        if (prefs.wantRecording()) {
            try {
                ContextCompat.startForegroundService(this,
                        new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_SCHEDULE_CHECK));
            } catch (Exception ignored) { }
        }
        Schedule.armAlarm(this);
        updateStatus();
    }

    private void updateStatus() {
        if (!prefs.scheduleEnabled()) {
            status.setText("현재: 예약 꺼짐 (녹화 켜면 항상 녹화)");
            return;
        }
        long now = System.currentTimeMillis();
        List<Schedule> list = prefs.schedules();
        if (Schedule.isActive(list, now)) {
            long end = Schedule.currentEnd(list, now);
            status.setText("현재: 예약 녹화 시간" + (end > 0 ? " (종료 " + Schedule.formatWhen(end) + ")" : ""));
        } else {
            long next = Schedule.nextStart(list, now);
            status.setText(next > 0 ? "다음 녹화 시작: " + Schedule.formatWhen(next) : "예약된 녹화 시간이 없습니다");
        }
        if (!prefs.wantRecording()) status.append("\n⚠ 메인 화면에서 '녹화 시작'을 눌러야 예약이 동작합니다");
    }

    private void edit(int index) {
        final Schedule s = index >= 0 ? items.get(index).copy() : new Schedule();
        int pad = dp(16);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView dl = new TextView(this);
        dl.setText("요일");
        box.addView(dl);
        LinearLayout days = new LinearLayout(this);
        days.setOrientation(LinearLayout.HORIZONTAL);
        final CheckBox[] cbs = new CheckBox[7];
        for (int i = 0; i < 7; i++) {
            CheckBox cb = new CheckBox(this);
            cb.setText(Schedule.DAY_NAMES[i]);
            cb.setChecked(s.hasDay(i));
            cbs[i] = cb;
            days.addView(cb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.addView(days);
        box.addView(hs);

        LinearLayout quick = new LinearLayout(this);
        String[] qn = {"매일", "평일", "주말"};
        int[] qm = {0b1111111, 0b0011111, 0b1100000};
        for (int q = 0; q < 3; q++) {
            final int mask = qm[q];
            Button b = new Button(this);
            b.setText(qn[q]);
            b.setOnClickListener(v -> {
                for (int i = 0; i < 7; i++) cbs[i].setChecked((mask & (1 << i)) != 0);
            });
            quick.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        box.addView(quick);

        final Button startBtn = new Button(this);
        final Button endBtn = new Button(this);
        startBtn.setText("시작  " + Schedule.hm(s.startMin));
        endBtn.setText("종료  " + Schedule.hm(s.endMin));
        startBtn.setOnClickListener(v -> new TimePickerDialog(this, (tp, h, m) -> {
            s.startMin = h * 60 + m;
            startBtn.setText("시작  " + Schedule.hm(s.startMin));
        }, s.startMin / 60, s.startMin % 60, true).show());
        endBtn.setOnClickListener(v -> new TimePickerDialog(this, (tp, h, m) -> {
            s.endMin = h * 60 + m;
            endBtn.setText("종료  " + Schedule.hm(s.endMin));
        }, s.endMin / 60, s.endMin % 60, true).show());
        LinearLayout times = new LinearLayout(this);
        times.addView(startBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        times.addView(endBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(times);

        final CheckBox enabled = new CheckBox(this);
        enabled.setText("이 예약 사용");
        enabled.setChecked(s.enabled);
        box.addView(enabled);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(index >= 0 ? "예약 수정" : "예약 추가")
                .setView(box)
                .setPositiveButton("저장", (d, w) -> {
                    int mask = 0;
                    for (int i = 0; i < 7; i++) if (cbs[i].isChecked()) mask |= 1 << i;
                    if (mask == 0) {
                        Toast.makeText(this, "요일을 하나 이상 선택하세요", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    s.days = mask;
                    s.enabled = enabled.isChecked();
                    if (index >= 0) items.set(index, s); else items.add(s);
                    save();
                    applyChanges();
                })
                .setNegativeButton("취소", null);
        if (index >= 0) {
            b.setNeutralButton("삭제", (d, w) -> {
                items.remove(index);
                save();
                applyChanges();
            });
        }
        b.show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
