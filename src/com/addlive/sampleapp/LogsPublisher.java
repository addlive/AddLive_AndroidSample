package com.addlive.sampleapp;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.io.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Simple utility class for publishing log files
 */

public class LogsPublisher {
  public void run(List<String> filter, Activity activity) {
    final ByteArrayOutputStream log_gz = collectLogs(filter);

    DateFormat formatter = new SimpleDateFormat("yyyyMMdd_HHmmss");
    String prefix = "addlive_logs_" + formatter.format(new Date()) + ".";

    Uri uri = null;
    try {
      File file = File.createTempFile(prefix, ".txt.gz",
				      activity.getExternalCacheDir());

      FileOutputStream stream = new FileOutputStream(file);
      stream.write(log_gz.toByteArray());

      uri = Uri.fromFile(file);
    } catch (IOException e) {
      Log.v(TAG, "Error while writing temporary log file");
    }

    Intent i = new Intent(Intent.ACTION_SEND);
    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    i.setType("application/x-gzip");
    i.putExtra(Intent.EXTRA_STREAM, uri);

    activity.startActivity(i);
  }

  private ByteArrayOutputStream collectLogs(List<String> filter) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();

    final StringBuilder log = new StringBuilder();

    List<String> commandLine = new ArrayList<String>();
    commandLine.add("logcat");
    commandLine.add("-d");
    commandLine.add("-v");
    commandLine.add("threadtime");
    commandLine.addAll(filter);

    String[] cl = new String[commandLine.size()];
    commandLine.toArray(cl);

    try {
      Process process = Runtime.getRuntime().exec(cl);

      BufferedReader reader = new BufferedReader(
	new InputStreamReader(process.getInputStream()));

      String line;
      while ((line = reader.readLine()) != null) {
        log.append(line);
	log.append('\n');
      }

      GZIPOutputStream gzip = new GZIPOutputStream(out);
      gzip.write(log.toString().getBytes());
      gzip.close();
    } catch (IOException e) {
      Log.v(TAG, "Error while executing logcat process");
    }

    return out;
  }

  private static final String TAG = "LogsPublisher";
}



