package io.eventlens;

import io.eventlens.cli.EventLensCli;

/**
 * Unified entry point for the EventLens fat JAR.
 * Defaults to 'serve' when launched with no arguments.
 */
public class EventLensMain {

    public static void main(String[] args) {
        if (args.length == 0) {
            args = new String[] { "serve" };
        }
        EventLensCli.main(args);
    }
}
