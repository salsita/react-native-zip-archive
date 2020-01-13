package com.rnziparchive;

import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.util.Timer;
import java.util.TimerTask;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipTask {
  private final String destFile;
  private final String[] files;
  private final String fromDirectory;
  private final Promise promise;
  private final ReactApplicationContext mCtx;
  private static final int BUFFER_SIZE = 4096;

  private long bytesRead = 0;
  private long totalSize;
  private RNZipArchiveModule rnZipArchiveModule;
  private String threadError;

  public ZipTask(String[] files, String destFile, String fromDirectory, Promise promise, RNZipArchiveModule rnZipArchiveModule, ReactApplicationContext mCtx) {
    this.destFile = destFile;
    this.files = files;
    this.fromDirectory = fromDirectory.endsWith("/") ? fromDirectory : fromDirectory + "/";
    this.promise = promise;
    this.rnZipArchiveModule = rnZipArchiveModule;
    this.mCtx = mCtx;
  }

  public void zip() {
    Thread.UncaughtExceptionHandler h = new Thread.UncaughtExceptionHandler() {
      public void uncaughtException(Thread th, Throwable ex) {
        promise.reject(null , "Uncaught exception in ZipTask: " + ex);
      }
    };

    Thread t = new Thread(new Runnable() {
      public void run() {
        Uri destUri = Uri.parse(destFile);;
        OutputStream dest;
        BufferedInputStream origin;
        boolean fromContentUri = "content".equals(destUri.getScheme());
        final String progressKey = fromContentUri ? fromDirectory : destFile;
        try {
          final long totalUncompressedBytes = getUncompressedSize(files);

          if (fromContentUri) {
            Uri dirUri = Uri.parse(destFile);
            Uri destPath = Uri.parse(fromDirectory);

            DocumentFile pickedDir = DocumentFile.fromTreeUri(mCtx, dirUri);

            String mimeType = URLConnection.guessContentTypeFromName(destPath.getLastPathSegment());
            Uri targetFileUri = pickedDir.createFile(mimeType, destPath.getLastPathSegment()).getUri();
            dest = mCtx.getContentResolver().openOutputStream(targetFileUri);
          } else {
            if (destFile.contains("/")) {
              File destDir = new File(destFile.substring(0, destFile.lastIndexOf("/")));
              if (!destDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                destDir.mkdirs();
              }
            }

            if (new File(destFile).exists()) {
              //noinspection ResultOfMethodCallIgnored
              new File(destFile).delete();
            }

            dest = new FileOutputStream(destFile);
          }

          ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(dest));

          byte data[] = new byte[BUFFER_SIZE];

          rnZipArchiveModule.updateProgress(0, 1, progressKey); // force 0%
          for (int i = 0; i < files.length; i++) {
            String absoluteFilepath = files[i];

            if (!new File(absoluteFilepath).isDirectory()) {
              FileInputStream fi = new FileInputStream(absoluteFilepath);
              String filename = absoluteFilepath.replace(fromDirectory, "");
              ZipEntry entry = new ZipEntry(filename);
              out.putNextEntry(entry);
              origin = new BufferedInputStream(fi, BUFFER_SIZE);
              int count;

              Timer timer = new Timer();
              timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                  rnZipArchiveModule.updateProgress(bytesRead, totalUncompressedBytes, progressKey);
                }
              }, 200, 200);

              while ((count = origin.read(data, 0, BUFFER_SIZE)) != -1) {
                out.write(data, 0, count);
                bytesRead += BUFFER_SIZE;
              }
              timer.cancel();
              origin.close();
            }
          }
          rnZipArchiveModule.updateProgress(1, 1, progressKey); // force 100%
          out.close();
        } catch (Exception ex) {
          ex.printStackTrace();
          rnZipArchiveModule.updateProgress(0, 1, progressKey); // force 0%
          promise.reject(null, String.format("Couldn't zip %s", progressKey));
        }
        promise.resolve(progressKey);
      }
    });

    t.setUncaughtExceptionHandler(h);
    t.start();
  }

  /**
   * Return the uncompressed size of the ZipFile (only works for files on disk, not in assets)
   *
   * @return -1 on failure
   */
  private long getUncompressedSize(String[] files) {
    long totalSize = 0;
    for (int i = 0; i < files.length; i++) {
      File file = new File(files[i]);
      long fileSize = file.length();
      if (fileSize != -1) {
        totalSize += fileSize;
      }
    }
    return totalSize;
  }
}
