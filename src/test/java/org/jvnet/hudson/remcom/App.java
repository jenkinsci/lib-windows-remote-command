package org.jvnet.hudson.remcom;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.jinterop.dcom.common.IJIAuthInfo;
import org.jinterop.dcom.common.JIDefaultAuthInfoImpl;
import org.jinterop.dcom.common.JIException;

import java.io.File;
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
        String pwd = FileUtils.readFileToString(new File("/home/kohsuke/.cubit")).trim();
        return new JIDefaultAuthInfoImpl("cloud", "kohsuke", pwd);
//        return new JIDefaultAuthInfoImpl("", "kohsuke", "kohsuke");
    }

    public static void main(String[] args) throws Exception {
        Logger.getLogger("org.jinterop").setLevel(Level.WARNING);
        Logger l = Logger.getLogger("org.jvnet.hudson");
        l.setLevel(Level.ALL);
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(Level.ALL);
        l.addHandler(ch);

//        String host = "10.1.6.71";
        String host = "10.1.68.89";

        installJdk(host);
//        blockingTest(host);

//        permissionTest(host);
    }

    private static void installJdk(String host) throws IOException, JIException, InterruptedException {
        WindowsRemoteProcessLauncher wrp = new WindowsRemoteProcessLauncher(host, createAuth());
        Process proc = wrp.launch("c:\\hudson\\jdk.exe /s \"/v/qn REBOOT=Suppress INSTALLDIR=c:\\hudson\\jdk /L c:\\hudson/jdk.exe.install.log\"", "c:\\");
        proc.getOutputStream().close();
        IOUtils.copy(proc.getInputStream(),System.out);

        System.out.println();
        System.out.println("Exit code="+proc.waitFor());
    }

    private static void blockingTest(String host) throws IOException, JIException, InterruptedException {
        WindowsRemoteProcessLauncher wrp = new WindowsRemoteProcessLauncher(host, createAuth());
        Process proc = wrp.launch("c:\\cygwin\\bin\\cat", "c:\\");
        IOUtils.copy(proc.getInputStream(),System.out);

        System.out.println();
        System.out.println("Exit code="+proc.waitFor());
    }

    private static void permissionTest(String host) throws IOException, JIException, InterruptedException {
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
