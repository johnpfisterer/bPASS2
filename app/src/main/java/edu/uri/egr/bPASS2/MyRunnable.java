package edu.uri.egr.bPASS2;

import android.graphics.Color;
import android.widget.TextView;

/**
 * Created by John Pfisterer on 11/20/2015.
 */
abstract public class MyRunnable implements Runnable {
    TextView textView;
    public MyRunnable() {
    }
    public void run(TextView textView, String color) {
        if (color == "RED")
            textView.setTextColor(Color.RED);
        if (color == "BLACK")
            textView.setTextColor(Color.BLACK);
    }
}

