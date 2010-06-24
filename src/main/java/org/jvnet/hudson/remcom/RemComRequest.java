package org.jvnet.hudson.remcom;

import java.io.IOException;

/**
 * Request data structure for starting a new program.
 *
 * @author Kohsuke Kawaguchi
 */
class RemComRequest extends Packet {
/*
   TCHAR szCommand[0x1000];
   TCHAR szWorkingDir[260];
   DWORD dwPriority;
   DWORD dwProcessId;
   TCHAR szMachine[260];
   BOOL  bNoWait;
 */
    String command;
    String workingDir;
    int priority = 0x20/*NORMAL PRIORITY*/;
    int processId;  /*should be random ID*/
    String machine; /*another unique ID*/
    boolean noWait;

    public byte[] pack() throws IOException {
        byte[] r = new byte[0x1000*2 + 260*2 + 4 + 4 + 260*2 + 4];

        setStrAt(command,    r, 0);
        setStrAt(workingDir, r, 0x1000*2);
        setIntAt(priority,   r, 0x1000*2 + 260*2);
        setIntAt(processId,  r, 0x1000*2 + 260*2 + 4);
        setStrAt(machine,    r, 0x1000*2 + 260*2 + 4 + 4);
        setIntAt(noWait?1:0, r, 0x1000*2 + 260*2 + 4 + 4 + 260*2);
        return r;
    }
}
