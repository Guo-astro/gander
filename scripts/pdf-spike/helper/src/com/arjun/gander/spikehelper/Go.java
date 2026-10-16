package com.arjun.gander.spikehelper;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies its PDF into its own files and hands it to Gander's PdfSpikeActivity with a one-off
 * read grant, as a chat or mail app shares an attachment.
 *
 *   adb shell am start -n com.arjun.gander.spikehelper/.Go [--es target com.arjun.gander.debug]
 */
public class Go extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            File dir = new File(getFilesDir(), "shared");
            dir.mkdirs();
            File file = new File(dir, "helper-spike.pdf");
            try (InputStream in = getAssets().open("spike.pdf");
                 OutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            }
            String target = getIntent().getStringExtra("target");
            if (target == null) target = "com.arjun.gander.debug";
            Intent view = new Intent("com.arjun.gander.PDF_SPIKE")
                    .setDataAndType(Uri.parse("content://com.arjun.gander.spikehelper.files/helper-spike.pdf"),
                            "application/pdf")
                    .setClassName(target, "com.arjun.gander.PdfSpikeActivity")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(view);
            Log.i("PdfSpikeHelper", "sent " + file.length() + " bytes to " + target);
        } catch (Exception e) {
            Log.e("PdfSpikeHelper", "could not send", e);
        }
        finish();
    }
}
