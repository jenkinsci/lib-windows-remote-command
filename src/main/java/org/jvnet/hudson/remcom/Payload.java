package org.jvnet.hudson.remcom;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * @author Kohsuke Kawaguchi
 */
class Payload extends Packet {
    public static Object/*byte[] or RemComResponse*/ read(DataInputStream in) throws IOException {
        byte[] len = new byte[4];
        in.readFully(len);
        int size = readInt(len,0);

        if (size==0) {
            // EOF from the service
            return new RemComResponse(in);
        }

        if (size<0)
            throw new IllegalArgumentException("Negative: "+size);
        byte[] buf = new byte[size];
        in.readFully(buf);

        return buf;
    }
    
    public static void write(byte[] data, int offset, int len, OutputStream dst) throws IOException {
        byte[] pack = new byte[len+4];
        setIntAt(len, pack, 0); // len
        System.arraycopy(data,offset, pack, 4, len);
        dst.write(pack);
    }
}
