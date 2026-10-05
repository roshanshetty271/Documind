package com.northeastern.csye7374.finalproject.actors;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Config for actor tests: a local (non-cluster) ActorSystem that still sees
 * the settings from application.conf (dispatchers, timeouts, thresholds).
 */
public final class TestConfigs {

    private TestConfigs() {}

    public static Config local() {
        return local("");
    }

    public static Config local(String overrides) {
        return ConfigFactory.parseString(
                overrides + "\n"
                + "akka.actor.provider = local\n"
                + "akka.coordinated-shutdown.exit-jvm = off\n"
                + "akka.coordinated-shutdown.run-by-jvm-shutdown-hook = off\n"
                + "akka.loglevel = WARNING\n")
            .withFallback(ConfigFactory.load());
    }
}
