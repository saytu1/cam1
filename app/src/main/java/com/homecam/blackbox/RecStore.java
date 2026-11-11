package com.homecam.blackbox;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.system.Os;
import android.system.StructStatVfs;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 녹화 파일 저장소.
 * - FileStore: 일반 파일 경로 (내장, 앱 전용 폴더, 경로로 접근 가능한 SD카드)
 * - SafStore : 시스템 폴더 선택기로 권한을 받은 폴더 (OTG USB 메모리·카드리더 등)
 */
public abstract class RecStore {
    private static final String TAG = "RecStore";

    /** 녹화 파일 1개 */
    public static final class Rec {
        public final String name;
        public final long size;
        public final long lastModified;
        @Nullable public final File file;
        @Nullable public final Uri uri;

        Rec(String name, long size, long lastModified, @Nullable File file, @Nullable Uri uri) {
            this.name = name;
            this.size = size;
            this.lastModified = lastModified;
            this.file = file;
            this.uri = uri;
        }

        /** 잠금 등에 쓰는 고유 키 */
        public String key() { return file != null ? file.getAbsolutePath() : String.valueOf(uri); }

        /** 표시용 위치 */
        public String location() {
            if (file != null) return file.getParent();
            return uri != null ? Uri.decode(uri.getLastPathSegment()) : "";
        }
    }

    /** 녹화 중인 출력 파일 */
    public static final class Output {
        public final String name;
        @Nullable public final String path;
        @Nullable public final ParcelFileDescriptor pfd;
        @Nullable public final Uri uri;

        Output(String name, @Nullable String path, @Nullable ParcelFileDescriptor pfd, @Nullable Uri uri) {
            this.name = name;
            this.path = path;
            this.pfd = pfd;
            this.uri = uri;
        }

        @Nullable public FileDescriptor fd() { return pfd != null ? pfd.getFileDescriptor() : null; }

        public long size() {
            try {
                if (path != null) return new File(path).length();
                if (pfd != null) return Os.fstat(pfd.getFileDescriptor()).st_size;
            } catch (Exception ignored) { }
            return -1;
        }

        public void close() {
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) { }
            }
        }
    }

    public abstract boolean isSaf();

    /** 사용자에게 보여줄 위치 */
    public abstract String displayPath();

    /** 저장소 이름 (내장 저장소 / SD카드 / USB 저장장치 …) */
    public abstract String label(Context ctx);

    /** 녹화 파일 목록 (오래된 순) */
    public abstract List<Rec> list();

    /** 새 녹화 파일 만들기. 실패 시 null */
    @Nullable public abstract Output create(Context ctx, String name);

    public abstract boolean delete(Context ctx, Rec r);

    /** 남은 공간 (모르면 -1) */
    public abstract long usableBytes();

    /** 전체 공간 (모르면 -1) */
    public abstract long totalBytes();

    /** 녹화 완료 처리 (미디어 스캔 등) */
    public void onFinished(Context ctx, Output o) { }

    /** 같은 저장소인지 비교용 */
    public abstract String id();

    static List<Rec> sortOldestFirst(List<Rec> l) {
        Collections.sort(l, (a, b) -> a.name.compareTo(b.name));
        return l;
    }

    static boolean isRecName(String n) {
        return n != null && n.startsWith(StorageHelper.PREFIX) && n.endsWith(".mp4");
    }

    // ================================================================ 파일 경로

    public static final class FileStore extends RecStore {
        public final File dir;

        public FileStore(File dir) { this.dir = dir; }

        @Override public boolean isSaf() { return false; }

        @Override public String displayPath() { return dir.getAbsolutePath(); }

        @Override public String label(Context ctx) { return StorageHelper.volumeLabel(ctx, dir); }

        @Override public String id() { return "file:" + dir.getAbsolutePath(); }

        @Override
        public List<Rec> list() {
            List<Rec> out = new ArrayList<>();
            File[] files = dir.listFiles((d, name) -> isRecName(name));
            if (files != null) {
                for (File f : files) out.add(new Rec(f.getName(), f.length(), f.lastModified(), f, null));
            }
            return sortOldestFirst(out);
        }

        @Nullable
        @Override
        public Output create(Context ctx, String name) {
            if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            return new Output(name, new File(dir, name).getAbsolutePath(), null, null);
        }

        @Override
        public boolean delete(Context ctx, Rec r) {
            if (r.file == null) return false;
            boolean ok = r.file.delete();
            if (ok) StorageHelper.scan(ctx, r.file);
            return ok;
        }

        @Override public long usableBytes() { return StorageHelper.usableBytes(dir); }

        @Override
        public long totalBytes() {
            try { return new android.os.StatFs(dir.getAbsolutePath()).getTotalBytes(); } catch (Exception e) { return -1; }
        }

        @Override
        public void onFinished(Context ctx, Output o) {
            if (o.path != null) StorageHelper.scan(ctx, new File(o.path));
        }
    }

    // ================================================================ 시스템 폴더 선택기 (SAF)

    public static final class SafStore extends RecStore {
        public final Uri tree;
        private final ContentResolver cr;
        private final Uri dirDoc;
        private final String treeDocId;

        public SafStore(Context ctx, Uri tree) {
            this.tree = tree;
            this.cr = ctx.getContentResolver();
            this.treeDocId = DocumentsContract.getTreeDocumentId(tree);
            this.dirDoc = DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId);
        }

        @Override public boolean isSaf() { return true; }

        @Override public String id() { return "saf:" + tree; }

        /** 예: "1234-ABCD:BlackBox" → "/1234-ABCD/BlackBox" */
        @Override
        public String displayPath() {
            String id = treeDocId;
            int c = id.indexOf(':');
            return c >= 0 ? "/" + id.substring(0, c) + "/" + id.substring(c + 1) : id;
        }

        @Override
        public String label(Context ctx) {
            String vol = treeDocId.contains(":") ? treeDocId.substring(0, treeDocId.indexOf(':')) : "";
            if ("primary".equalsIgnoreCase(vol)) return "내장 저장소";
            // 시스템이 알려주는 장치 이름 (예: "USB 저장소", "SanDisk")
            for (StorageHelper.Volume v : StorageHelper.volumes(ctx)) {
                if (v.root.getName().equalsIgnoreCase(vol)) return v.label;
            }
            String title = rootTitle();
            return title != null ? title : "USB/외부 저장장치";
        }

        /** 폴더에 접근 가능한지 (장치가 빠지거나 권한이 사라지면 false) */
        public boolean isAccessible() {
            try (Cursor c = cr.query(dirDoc, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                    null, null, null)) {
                return c != null && c.moveToFirst();
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public List<Rec> list() {
            List<Rec> out = new ArrayList<>();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocId);
            String[] proj = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
            };
            try (Cursor c = cr.query(children, proj, null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        String name = c.getString(1);
                        if (!isRecName(name)) continue;
                        Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                        out.add(new Rec(name, c.isNull(2) ? 0 : c.getLong(2), c.isNull(3) ? 0 : c.getLong(3), null, u));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "saf list", e);
            }
            return sortOldestFirst(out);
        }

        @Nullable
        @Override
        public Output create(Context ctx, String name) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null; // MediaMuxer(FileDescriptor) 는 Android 8+
            try {
                Uri u = DocumentsContract.createDocument(cr, dirDoc, "video/mp4", name);
                if (u == null) return null;
                ParcelFileDescriptor pfd = cr.openFileDescriptor(u, "rw");
                if (pfd == null) {
                    DocumentsContract.deleteDocument(cr, u);
                    return null;
                }
                return new Output(name, null, pfd, u);
            } catch (Exception e) {
                Log.e(TAG, "saf create", e);
                return null;
            }
        }

        @Override
        public boolean delete(Context ctx, Rec r) {
            if (r.uri == null) return false;
            try {
                return DocumentsContract.deleteDocument(cr, r.uri);
            } catch (Exception e) {
                Log.w(TAG, "saf delete", e);
                return false;
            }
        }

        /** 이 폴더가 속한 저장장치 루트 정보 (남은 공간·전체 공간·이름) */
        @Nullable
        private Cursor queryRoot() {
            String authority = tree.getAuthority();
            String vol = treeDocId.contains(":") ? treeDocId.substring(0, treeDocId.indexOf(':') + 1) : treeDocId;
            try {
                Cursor c = cr.query(DocumentsContract.buildRootsUri(authority), null, null, null, null);
                if (c == null) return null;
                int docIdx = c.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID);
                while (c.moveToNext()) {
                    String d = docIdx >= 0 ? c.getString(docIdx) : null;
                    if (d != null && (d.equals(vol) || treeDocId.startsWith(d))) return c;
                }
                c.close();
            } catch (Exception e) {
                Log.w(TAG, "saf roots", e);
            }
            return null;
        }

        @Nullable
        private String rootTitle() {
            try (Cursor c = queryRoot()) {
                if (c == null) return null;
                int i = c.getColumnIndex(DocumentsContract.Root.COLUMN_TITLE);
                return i >= 0 ? c.getString(i) : null;
            } catch (Exception e) {
                return null;
            }
        }

        private long rootLong(String col) {
            try (Cursor c = queryRoot()) {
                if (c == null) return -1;
                int i = c.getColumnIndex(col);
                return (i >= 0 && !c.isNull(i)) ? c.getLong(i) : -1;
            } catch (Exception e) {
                return -1;
            }
        }

        /** 루트 정보가 없으면 가장 최근 파일의 파일시스템 정보로 계산 */
        @Nullable
        private StructStatVfs statVfs() {
            List<Rec> l = list();
            if (l.isEmpty()) return null;
            Rec r = l.get(l.size() - 1);
            try (ParcelFileDescriptor p = cr.openFileDescriptor(r.uri, "r")) {
                return p != null ? Os.fstatvfs(p.getFileDescriptor()) : null;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public long usableBytes() {
            long v = rootLong(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES);
            if (v >= 0) return v;
            StructStatVfs s = statVfs();
            return s != null ? s.f_bavail * s.f_bsize : -1;
        }

        @Override
        public long totalBytes() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                long v = rootLong(DocumentsContract.Root.COLUMN_CAPACITY_BYTES);
                if (v > 0) return v;
            }
            StructStatVfs s = statVfs();
            return s != null ? s.f_blocks * s.f_bsize : -1;
        }

        /** 실제로 파일을 만들고 지울 수 있는지 (폴더 선택 시 1회 확인) */
        public boolean testWrite() {
            try {
                Uri u = DocumentsContract.createDocument(cr, dirDoc, "application/octet-stream", ".bb_write_test");
                if (u == null) return false;
                DocumentsContract.deleteDocument(cr, u);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "saf write test", e);
                return false;
            }
        }
    }
}
