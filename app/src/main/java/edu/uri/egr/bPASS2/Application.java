package edu.uri.egr.bPASS2;

import edu.uri.egr.hermes.Hermes;

/**
 * Created by cody on 10/8/15.
 * Edited by John on 10/28/15.
 */
public class Application extends android.app.Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Hermes.Config config = new Hermes.Config()
                .enableDebug(true);

        Hermes.init(this, config);
    }
}
