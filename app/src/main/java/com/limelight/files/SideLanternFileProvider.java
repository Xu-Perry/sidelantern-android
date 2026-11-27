package com.limelight.files;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/**
 * Zero-dependency read-only ContentProvider for previewing and sharing
 * completed downloaded files stored inside files/SideLanternTransfers/.
 */
public class SideLanternFileProvider extends ContentProvider {
    public static String getAuthority(Context context) {
        return "files." + context.getPackageName();
    }

    public static Uri getUriForFile(Context context, File file) {
        File base = new File(context.getFilesDir(), "SideLanternTransfers");
        try {
            String baseCanonical = base.getCanonicalPath();
            String fileCanonical = file.getCanonicalPath();
            if (!fileCanonical.startsWith(baseCanonical + File.separator)) {
                return null;
            }
            String relative = fileCanonical.substring(baseCanonical.length() + 1);
            Uri.Builder builder = new Uri.Builder()
                    .scheme("content")
                    .authority(getAuthority(context));
            for (String segment : relative.split(File.separator.equals("\\") ? "\\\\" : File.separator)) {
                builder.appendPath(segment);
            }
            return builder.build();
        } catch (IOException e) {
            return null;
        }
    }

    private File resolveFile(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        if (context == null || uri == null) {
            throw new FileNotFoundException("Missing context or URI");
        }
        File base = new File(context.getFilesDir(), "SideLanternTransfers");
        File target = base;
        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.isEmpty()) {
            throw new FileNotFoundException("Empty path");
        }
        for (String segment : segments) {
            if (segment == null || segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || segment.contains("/") || segment.contains("\\")) {
                throw new FileNotFoundException("Invalid path segment");
            }
            target = new File(target, segment);
        }
        try {
            String baseCanonical = base.getCanonicalPath();
            String targetCanonical = target.getCanonicalPath();
            if (!targetCanonical.startsWith(baseCanonical + File.separator) || !target.isFile()) {
                throw new FileNotFoundException("File not accessible");
            }
            return target;
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = resolveFile(uri);
            String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor cursor = new MatrixCursor(cols, 1);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                    row[i] = file.getName();
                } else if (OpenableColumns.SIZE.equals(cols[i])) {
                    row[i] = file.length();
                }
            }
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        try {
            File file = resolveFile(uri);
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot + 1).toLowerCase();
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (mime != null) {
                    return mime;
                }
            }
        } catch (FileNotFoundException ignored) {
        }
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !"r".equals(mode)) {
            throw new SecurityException("Read-only provider");
        }
        File file = resolveFile(uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
