package org.succlz123.cinelli.sample;

import android.app.Activity;
import android.os.Bundle;
import org.succlz123.cinelli.Canceller;
import org.succlz123.cinelli.Task;


/**
 * Created by succlz123 on 2018/12/8.
 */
public final class MainActivity extends Activity {
    Canceller canceller = new Canceller();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Task.callInBackground(() -> {
            Thread.sleep(233);
            return null;
        }, canceller).continueWith(objectTask -> null);
    }

    @Override
    protected void onDestroy() {
        canceller.cancel();
        super.onDestroy();
    }
}
