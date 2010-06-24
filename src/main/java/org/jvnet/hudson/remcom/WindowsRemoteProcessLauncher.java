package org.jvnet.hudson.remcom;

import jcifs.smb.NtStatus;
import jcifs.smb.NtlmPasswordAuthentication;
import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbNamedPipe;
import org.jinterop.dcom.common.IJIAuthInfo;
import org.jinterop.dcom.common.JIException;
import org.jinterop.dcom.core.JISession;
import org.jvnet.hudson.wmi.SWbemServices;
import org.jvnet.hudson.wmi.WMI;
import org.jvnet.hudson.wmi.Win32Service;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
import static jcifs.smb.SmbNamedPipe.PIPE_TYPE_RDWR;
import static org.jvnet.hudson.wmi.Win32Service.Win32OwnProcess;

/**
 * Start a Windows process remotely.
 *
 * <p>
 * This mechanism depends on the RPC and DCOM. We first remotely create a service
 * on the target machine and starts it. This service will accept a named pipe
 * connection, which is used to launch the process and shuttle back and forth
 * stdin and stdout+stderr.
 *
 * @author Kohsuke Kawaguchi
 */
public class WindowsRemoteProcessLauncher {
    private final String hostName;
    private final IJIAuthInfo credential;
    private int timeout = 5000;
    private final Random random = new Random();

    /**
     * @param hostName
     *      Remote Windows host name or IP address to connect to.
     * @param credential
     *      User account on the target Windows machine. This needs to have sufficient privilege
     *      to access administrative shares and install a service. Normally you have to be an
     *      administrator to be able to do this.
     */
    public WindowsRemoteProcessLauncher(String hostName, IJIAuthInfo credential) {
        this.hostName = hostName;
        this.credential = credential;
    }

    /**
     * Sets the connection timeout in milli-seconds.
     * Default is 5000.
     */
    public void setTimeout(int milliseconds) {
        this.timeout = milliseconds;
    }

    private NtlmPasswordAuthentication createSmbAuth() throws IOException {
        return new NtlmPasswordAuthentication(credential.getDomain(), credential.getUserName(), credential.getPassword());
    }

    /**
     * Launches a process remotely.
     *
     * <p>
     * The resulting {@link Process} behaves slightly differently from a normal local {@link Process}
     * in the following ways:
     *
     * <ul>
     * <li>stderr and stdout are bundled together into {@link Process#getOutputStream()}.
     *     This behavior is like {@code ProcessBuilder.redirectErrorStream(true)}.
     * <li>you need to fully drain the output from the process before you can notice
     *     that the process has terminated, because the stdout and exit code travels
     *     over the same connection.
     * <li>Without calling {@link Process#waitFor()}, {@link Process#exitValue()} will
     *     never return an exit code.
     * </ul>
     *
     * The communication channel to the remote Windows machine is terminated only when
     * you check the exit code or when you destroy the process, so make sure to do so.
     *
     * @param command
     *      Command to execute. Note that on Windows the command line argument is a single string, unlike Unix.
     *      The implementation executes this through cmd.exe, so one can execute cmd.exe internal commands,
     *      such as echo, copy, etc.
     * @param workingDirectory
     *      The working directory to launch the process with.
     */
    public Process launch(String command, String workingDirectory) throws IOException, JIException, InterruptedException {
        JISession session = JISession.createSession(credential);
        session.setGlobalSocketTimeout(60000);
        SWbemServices services = WMI.connect(session, hostName);

        NtlmPasswordAuthentication smbAuth = createSmbAuth();

        if (true) { // change to false if the server side is under the debugger launched from CLI.
            Win32Service rsvc = services.getService("RemComSVC");
            if (rsvc==null) {
                LOGGER.fine("Creating a service");
                SmbFile remComSvcExe = new SmbFile("smb://" + hostName + "/ADMIN$/RemComSvc.exe", smbAuth);
                copyAndClose(WindowsRemoteProcessLauncher.class.getResourceAsStream("RemComSvc.exe"), remComSvcExe.getOutputStream());

                Win32Service svc = services.Get("Win32_Service").cast(Win32Service.class);
                int r = svc.Create("RemComSvc","Remote Communication Service",
                        "%SystemRoot%\\RemComSvc.exe",
                        Win32OwnProcess, 1, "Manual", false);
                if(r!=0)
                    throw new IOException("Failed to register a service");

                Thread.sleep(1000);
                rsvc = services.getService("RemComSVC");
            }
            if (!rsvc.State().equals("Running")) {
                LOGGER.fine("Starting a service");
                rsvc = services.getService("RemComSVC");
                rsvc.start();
            }
        }

        String path = "smb://" + hostName + "/IPC$/pipe/RemCom_communicaton"; // trap!!
        LOGGER.fine("Trying to connect to "+path);
        final SmbNamedPipe comm = new SmbNamedPipe( path, PIPE_TYPE_RDWR, smbAuth);
        final DataInputStream in = new DataInputStream(new BufferedInputStream(openForRead(comm)));
        final OutputStream out = openForWrite(comm);

        LOGGER.fine("Sending launch request");
        RemComRequest req = new RemComRequest();
        req.command = command;
        req.workingDir = workingDirectory;
        req.machine = Integer.toHexString(hashCode());
        req.processId = random.nextInt(65536);
        out.write(req.pack());

        final RemComResponse[] result = new RemComResponse[1];

        final OutputStream stdout = new OutputStream() {
            boolean closed;
            @Override
            public void write(int b) throws IOException {
              write(new byte[]{(byte)b},0,1); // TODO
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (closed) throw new IOException("stream is already closed");
                if (len==0) return;
                Payload.write(b,off,len,out);
            }

            @Override
            public void close() throws IOException {
                if (!closed) {
                    closed = true;
                    out.write(new byte[4]);   // EOF signal
                }
            }
        };
        final InputStream stdin = new InputStream() {
            private byte[] buf;
            private int remaining;
            private boolean eof;
            @Override
            public int read() throws IOException {
                if (!fetch())
                    return -1;
                return ((int)(buf[buf.length-(remaining--)]))&0xFF;
            }

            private boolean fetch() throws IOException {
                if (eof)    return false;

                if (remaining==0) {
                    Object o = Payload.read(in);
                    if (o instanceof RemComResponse) {
                        result[0] = (RemComResponse) o;
                        synchronized (result) {
                            result.notifyAll();
                        }
                        eof = true;
                        return false;
                    } else {
                        buf = (byte[])o;
                        remaining = buf.length;
                    }
                }
                return true;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (eof || !fetch())   return -1;
                int sz = Math.min(len,remaining);
                System.arraycopy(buf,buf.length-remaining, b, off, sz);
                remaining -= sz;
                return sz;
            }
        };

        final JISession s = session;
        return new Process() {
            private Integer exitCode;

            public OutputStream getOutputStream() {return stdout; }
            public InputStream getInputStream() { return stdin; }
            public InputStream getErrorStream() { return NULL; }

            @Override
            public synchronized int waitFor() throws InterruptedException {
                synchronized (result) {
                    while (result[0]==null)
                        result.wait();
                    
                    if (result[0].errorCode!=0)
                        exitCode = 10000000+result[0].errorCode;
                    exitCode = result[0].returnCode;
                    destroy();
                    return exitCode;
                }
            }

            @Override
            public synchronized int exitValue() {
                if (exitCode==null) throw new IllegalStateException();
                return exitCode;
            }

            @Override
            public synchronized void destroy() {
                if (exitCode==null) exitCode=-1;
                try {
                    JISession.destroySession(s);
                } catch (JIException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    /**
     * Opens a pipe for write, with the equivalent of "WaitNamedPipe" API call
     */
    private OutputStream openForWrite(SmbNamedPipe pipe ) throws IOException, InterruptedException {
        long start = System.currentTimeMillis();
        while (true) {
            try {
                return pipe.getNamedPipeOutputStream();
            } catch (SmbException e) {
                if (e.getNtStatus()!= NtStatus.NT_STATUS_PIPE_BUSY)
                    throw e;

                if (start+timeout < System.currentTimeMillis())
                    throw e;

                // wait and retry
                Thread.sleep(500);
            }
        }
    }

    private InputStream openForRead(SmbNamedPipe pipe ) throws IOException, InterruptedException {
        long start = System.currentTimeMillis();
        while (true) {
            try {
                return pipe.getNamedPipeInputStream();
            } catch (SmbException e) {
                if (e.getNtStatus()!=NtStatus.NT_STATUS_PIPE_BUSY)
                    throw e;

                if (start+timeout < System.currentTimeMillis())
                    throw e;

                // wait and retry
                Thread.sleep(500);
            }
        }
    }

    private void copyAndClose(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] buf = new byte[4096];
            while (true) {
                int len = in.read(buf);
                if (len<0)  return;
                out.write(buf,0,len);
            }
        } finally {
            close(in);
            close(out);
        }
    }

    private void close(Closeable c) {
        try {
            c.close();
        } catch (IOException e) {
            LOGGER.log(FINE,"Failed to close a stream",e);
        }
    }

    private static final Logger LOGGER = Logger.getLogger(WindowsRemoteProcessLauncher.class.getName());
    private static final InputStream NULL = new ByteArrayInputStream(new byte[0]);
}
