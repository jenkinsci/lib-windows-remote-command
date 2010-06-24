package org.jvnet.hudson.remcom;

import org.apache.commons.io.IOUtils;
import org.jinterop.dcom.common.IJIAuthInfo;
import org.jinterop.dcom.common.JIDefaultAuthInfoImpl;
import org.jvnet.hudson.remcom.WindowsRemoteProcessLauncher;

import java.io.IOException;
import java.io.OutputStream;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Debug code.
 */
public class App {
    private static IJIAuthInfo createAuth() throws IOException {
        return new JIDefaultAuthInfoImpl("", "kohsuke", "kohsuke");
    }

    public static void main(String[] args) throws Exception {
        Logger.getLogger("org.jinterop").setLevel(Level.WARNING);
        Logger l = Logger.getLogger("org.jvnet.hudson");
        l.setLevel(Level.ALL);
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(Level.ALL);
        l.addHandler(ch);
        
        String host = "10.1.6.71";

        WindowsRemoteProcessLauncher wrp = new WindowsRemoteProcessLauncher(host, createAuth());
        Process proc = wrp.launch("c:\\cygwin\\bin\\cat > iAmHere", "c:\\");
        OutputStream o = proc.getOutputStream();
        o.write("Hello world".getBytes());
        o.close();
        IOUtils.copy(proc.getInputStream(),System.out);
        IOUtils.copy(proc.getErrorStream(),System.out);

        System.out.println();
        System.out.println("Exit code="+proc.waitFor());


        proc = wrp.launch("dir", "c:\\");

        o = proc.getOutputStream();
        o.write("Hello world".getBytes());
        o.close();
        IOUtils.copy(proc.getInputStream(),System.out);
        IOUtils.copy(proc.getErrorStream(),System.out);

        System.out.println();
        System.out.println("Exit code="+proc.waitFor());
    }
}
