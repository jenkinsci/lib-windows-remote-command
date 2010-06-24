package org.jvnet.hudson.remcom;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * Final process exit code from the service.
 *
 * @author Kohsuke Kawaguchi
 */
class RemComResponse extends Packet {
    int errorCode;
    int returnCode;

    RemComResponse(DataInputStream in) throws IOException {
        byte[] r = new byte[8];
        in.readFully(r);
        errorCode = readInt(r,0);
        returnCode = readInt(r,4);
    }
}
