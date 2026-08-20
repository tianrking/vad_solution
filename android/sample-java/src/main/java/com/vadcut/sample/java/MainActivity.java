package com.vadcut.sample.java;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.vadcut.android.TrimConfig;
import com.vadcut.android.TrimException;
import com.vadcut.android.TrimListener;
import com.vadcut.android.TrimPreset;
import com.vadcut.android.TrimProgress;
import com.vadcut.android.TrimRequest;
import com.vadcut.android.TrimResult;
import com.vadcut.android.TrimTask;
import com.vadcut.android.VadCut;

import java.io.File;

public final class MainActivity extends Activity {
    private static final int REQUEST_INPUT = 100;
    private static final int REQUEST_OUTPUT = 101;

    private TextView status;
    private Uri inputUri;
    private TrimTask task;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setText("Choose a recording. VadCut runs entirely on this device.");
        status.setTextSize(16f);

        Button choose = new Button(this);
        choose.setText("Choose and trim audio");
        choose.setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            startActivityForResult(intent, REQUEST_INPUT);
        });

        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(view -> {
            if (task != null) task.cancel();
        });

        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding, padding, padding);
        layout.addView(status, new ViewGroup.LayoutParams(-1, -2));
        layout.addView(choose, new ViewGroup.LayoutParams(-1, -2));
        layout.addView(cancel, new ViewGroup.LayoutParams(-1, -2));
        setContentView(layout);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_INPUT) {
            inputUri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(inputUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) {
                // Some document providers do not offer persistable grants; the current grant still works.
            }
            Intent output = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            output.addCategory(Intent.CATEGORY_OPENABLE);
            output.setType("audio/mp4");
            output.putExtra(Intent.EXTRA_TITLE, "vadcut-output.m4a");
            startActivityForResult(output, REQUEST_OUTPUT);
        } else if (requestCode == REQUEST_OUTPUT && inputUri != null) {
            process(inputUri, data.getData());
        }
    }

    private void process(Uri input, Uri output) {
        TrimConfig config = TrimConfig.fromPreset(TrimPreset.VOICE_MEMO);
        TrimRequest request = new TrimRequest.Builder(input, output).setConfig(config).build();
        task = VadCut.with(this).trimAsync(request, new TrimListener() {
            @Override
            public void onProgress(TrimProgress progress) {
                status.setText(progress.getPhase() + ": " + progress.getPercent() + "%");
            }

            @Override
            public void onSuccess(TrimResult result) {
                task = null;
                status.setText("Done. Removed " + result.getRemovedDurationMs() + " ms; output: " + output);
            }

            @Override
            public void onError(TrimException error) {
                task = null;
                deleteCreatedOutput(output);
                status.setText("Failed [" + error.getCode() + "]: " + error.getMessage());
            }

            @Override
            public void onCancelled() {
                task = null;
                deleteCreatedOutput(output);
                status.setText("Cancelled");
            }
        });
    }

    private void deleteCreatedOutput(Uri output) {
        // ACTION_CREATE_DOCUMENT created this URI specifically for the current task.
        try {
            if (ContentResolver.SCHEME_CONTENT.equals(output.getScheme())) {
                if (DocumentsContract.isDocumentUri(this, output)) {
                    DocumentsContract.deleteDocument(getContentResolver(), output);
                } else {
                    getContentResolver().delete(output, null, null);
                }
            } else if (ContentResolver.SCHEME_FILE.equals(output.getScheme()) && output.getPath() != null) {
                //noinspection ResultOfMethodCallIgnored
                new File(output.getPath()).delete();
            }
        } catch (Exception ignored) {
            // Best-effort cleanup; the provider may have revoked the grant.
        }
    }
}
