package io.eventlens.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "eventlens", mixinStandardHelpOptions = true, version = "EventLens 1.0.0", description = "Event Store Visual Debugger & Time Machine", subcommands = {
        ServeCommand.class,
        ReplayCommand.class,
        BisectCommand.class,
        DiffCommand.class,
        ExportCommand.class
})
public class EventLensCli implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new EventLensCli()).execute(args);
        System.exit(exitCode);
    }
}
