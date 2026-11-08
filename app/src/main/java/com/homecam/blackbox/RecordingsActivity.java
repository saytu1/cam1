package com.homecam.blackbox;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 녹화 파일 목록 (최신 순). 터치 = 재생, 길게 누르기 = 삭제 */
public class RecordingsActivity extends AppCompatActivity {
    private final List<File> files = new ArrayList<>();
    private ArrayAdapter<File> adapter;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ListView list = new ListView(this);
        empty = new TextView(this);
        empty.setText("녹화 파일이 없습니다");
        empty.setPadding(40, 40, 40, 40);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.addView(list);
        root.addView(empty);
        list.setEmptyView(empty);
        setContentView(root);

        adapter = new ArrayAdapter<File>(this, android.R.layout.simple_list_item_2, android.R.id.text1, files) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                File f = getItem(position);
                ((TextView) v.findViewById(android.R.id.text1)).setText(pretty(f.getName()));
                ((TextView) v.findViewById(android.R.id.text2)).setText(
                        StorageHelper.formatBytes(f.length()) + "  ·  " + f.getParentFile().getParent());
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> play(files.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            File f = files.get(pos);
            new AlertDialog.Builder(this)
                    .setTitle("삭제")
                    .setMessage(f.getName() + " 파일을 삭제할까요?")
                    .setPositiveButton("삭제", (d, w) -> {
                        if (f.delete()) reload();
                    })
                    .setNegativeButton("취소", null)
                    .show();
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        files.clear();
        files.addAll(StorageHelper.listAllRecordingsNewestFirst(this));
        adapter.notifyDataSetChanged();
        setTitle(getString(R.string.recordings) + " (" + files.size() + ")");
    }

    /** BB_20261108_153000.mp4 -> 2026-11-08 15:30:00 */
    private static String pretty(String name) {
        try {
            String s = name.substring(StorageHelper.PREFIX.length(), name.length() - 4);
            return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8) + " "
                    + s.substring(9, 11) + ":" + s.substring(11, 13) + ":" + s.substring(13, 15);
        } catch (Exception e) {
            return name;
        }
    }

    private void play(File f) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "재생할 수 있는 앱이 없습니다: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
