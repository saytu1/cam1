package com.homecam.blackbox;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 녹화 저장 폴더 선택 / 새 폴더 만들기 (내장 저장소 · SD카드) */
public class FolderPickerActivity extends AppCompatActivity {
    private static final String UP = "⬆  상위 폴더";

    private Prefs prefs;
    private File current;
    private File volumeRoot;
    private TextView pathView, permBanner;
    private Button permButton;
    private LinearLayout volRow;
    private final List<String> names = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    /** 모든 파일 액세스 권한 요청 화면 열기 */
    public static void requestAllFilesAccess(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + a.getPackageName())));
            } catch (Exception e) {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            ActivityCompat.requestPermissions(a, new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 20);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("저장 폴더 선택");
        prefs = new Prefs(this);
        int pad = dp(12);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 권한 안내
        permBanner = new TextView(this);
        permBanner.setText("⚠ '모든 파일 액세스' 권한이 없어 원하는 폴더에 저장할 수 없습니다.");
        permBanner.setBackgroundColor(0xFFFFF3E0);
        permBanner.setTextColor(0xFF5D4037);
        permBanner.setPadding(pad, pad, pad, pad / 2);
        root.addView(permBanner);
        permButton = new Button(this);
        permButton.setText("모든 파일 액세스 허용");
        permButton.setOnClickListener(v -> requestAllFilesAccess(this));
        root.addView(permButton);

        // 저장소 선택 (내장 / SD)
        HorizontalScrollView hs = new HorizontalScrollView(this);
        volRow = new LinearLayout(this);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volRow.setPadding(pad / 2, pad / 2, pad / 2, 0);
        hs.addView(volRow);
        root.addView(hs);

        pathView = new TextView(this);
        pathView.setPadding(pad, pad / 2, pad, pad / 2);
        pathView.setTextSize(15);
        pathView.setBackgroundColor(0xFF263238);
        pathView.setTextColor(Color.WHITE);
        root.addView(pathView);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            String n = names.get(pos);
            if (UP.equals(n)) {
                File parent = current.getParentFile();
                if (parent != null && !current.equals(volumeRoot)) open(parent);
            } else {
                open(new File(current, n.substring(3)));
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        Button mk = new Button(this);
        mk.setText("새 폴더");
        mk.setOnClickListener(v -> createFolder());
        Button def = new Button(this);
        def.setText("기본값");
        def.setOnClickListener(v -> {
            File d = StorageHelper.defaultFolder();
            volumeRoot = rootOf(d);
            if (!d.exists()) //noinspection ResultOfMethodCallIgnored
                d.mkdirs();
            open(d.exists() ? d : d.getParentFile());
        });
        Button ok = new Button(this);
        ok.setText("이 폴더에 저장");
        ok.setOnClickListener(v -> choose());
        LinearLayout.LayoutParams w1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        buttons.addView(mk, w1);
        buttons.addView(def, new LinearLayout.LayoutParams(w1));
        buttons.addView(ok, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        root.addView(buttons);

        setContentView(root);

        File start = StorageHelper.desiredFolder(this);
        volumeRoot = rootOf(start);
        while (start != null && !start.isDirectory()) start = start.getParentFile();
        open(start != null ? start : volumeRoot);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean has = StorageHelper.hasAllFilesAccess(this);
        permBanner.setVisibility(has ? View.GONE : View.VISIBLE);
        permButton.setVisibility(has ? View.GONE : View.VISIBLE);
        StorageHelper.invalidateCache();
        buildVolumeButtons();
        if (current != null) open(current);
    }

    /** 저장장치 버튼 (USB/OTG 장치를 꽂고 돌아오면 다시 갱신) */
    private void buildVolumeButtons() {
        volRow.removeAllViews();
        for (StorageHelper.Volume v : StorageHelper.volumes(this)) {
            Button b = new Button(this);
            b.setText(v.label + " (여유 " + StorageHelper.formatBytes(StorageHelper.usableBytes(v.root)) + ")");
            b.setAllCaps(false);
            b.setOnClickListener(x -> {
                volumeRoot = v.root;
                open(v.root);
            });
            volRow.addView(b);
        }
        Button manual = new Button(this);
        manual.setText("경로 직접 입력");
        manual.setAllCaps(false);
        manual.setOnClickListener(x -> enterPath());
        volRow.addView(manual);
    }

    private void enterPath() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setText(current != null ? current.getAbsolutePath() : "/storage/");
        new AlertDialog.Builder(this)
                .setTitle("폴더 경로 입력")
                .setMessage("예: /storage/1234-ABCD/BlackBox\n(OTG 장치는 보통 /storage/ 아래에 있습니다)")
                .setView(et)
                .setPositiveButton("열기", (d, w) -> {
                    File f = new File(et.getText().toString().trim());
                    if (!f.isDirectory() && !f.mkdirs()) {
                        toast("폴더를 열거나 만들 수 없습니다");
                        return;
                    }
                    volumeRoot = rootOf(f);
                    open(f);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        onResume();
    }

    private File rootOf(File f) {
        String p = f.getAbsolutePath();
        File best = null;
        for (StorageHelper.Volume v : StorageHelper.volumes(this)) {
            String r = v.root.getAbsolutePath();
            if (p.startsWith(r) && (best == null || r.length() > best.getAbsolutePath().length())) best = v.root;
        }
        return best != null ? best : StorageHelper.volumes(this).get(0).root;
    }

    private void open(File dir) {
        current = dir;
        pathView.setText("📁 " + StorageHelper.volumeLabel(this, dir) + "\n" + dir.getAbsolutePath());
        names.clear();
        if (!dir.equals(volumeRoot)) names.add(UP);
        File[] subs = dir.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
        if (subs != null) {
            Arrays.sort(subs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : subs) names.add("📁 " + f.getName());
        }
        adapter.notifyDataSetChanged();
    }

    private void createFolder() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint("폴더 이름");
        et.setText("BlackBox");
        et.selectAll();
        new AlertDialog.Builder(this)
                .setTitle("새 폴더 만들기")
                .setMessage(current.getAbsolutePath() + " 안에 만듭니다")
                .setView(et)
                .setPositiveButton("만들기", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty() || name.contains("/") || name.startsWith(".")) {
                        toast("올바른 폴더 이름이 아닙니다");
                        return;
                    }
                    File nf = new File(current, name);
                    if (nf.isDirectory() || nf.mkdirs()) {
                        open(nf);
                    } else {
                        toast("폴더를 만들 수 없습니다 (권한 또는 위치 확인)");
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void choose() {
        StorageHelper.invalidateCache();
        if (!StorageHelper.isWritable(current)) {
            new AlertDialog.Builder(this)
                    .setTitle("쓸 수 없는 폴더")
                    .setMessage("이 폴더에는 파일을 저장할 수 없습니다.\n"
                            + (StorageHelper.hasAllFilesAccess(this)
                            ? "다른 폴더를 선택하세요. (일부 기기는 SD카드 루트에 쓰기를 막습니다)"
                            : "'모든 파일 액세스' 권한을 먼저 허용하세요."))
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }
        prefs.setFolder(current.getAbsolutePath());
        StorageHelper.invalidateCache();
        toast("저장 폴더 변경: " + current.getAbsolutePath() + "\n(다음 파일부터 적용)");
        finish();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
