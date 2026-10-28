package com.example.casc;

import com.example.casc.cli.Cli;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        int code = Cli.run(args, System.in, out, err);
        out.flush();
        err.flush();
        System.exit(code);
    }
}
