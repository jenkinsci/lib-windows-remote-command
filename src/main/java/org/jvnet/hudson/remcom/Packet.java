package org.jvnet.hudson.remcom;

import java.io.UnsupportedEncodingException;

/**
 * Low-level data packing utility code.
 *
 * @author Kohsuke Kawaguchi
 */
abstract class Packet {
    static void setIntAt(int value, byte[] r, int offset) {
        r[offset  ] = (byte)(value&0xFF);
        r[offset+1] = (byte)((value>> 8)&0xFF);
        r[offset+2] = (byte)((value>>16)&0xFF);
        r[offset+3] = (byte)((value>>24)&0xFF);
    }

    static int readInt(byte[] r, int i) {
        return (toI(r[i]))|(toI(r[i+1])<<8)|(toI(r[i+2])<<16)|(toI(r[i+3])<<24);
    }

    static int toI(byte b) {
        return ((int)b)&0xFF;
    }

    static void setStrAt(String s, byte[] r, int offset) throws UnsupportedEncodingException {
        arraycopy( s.getBytes("UTF-16LE"), r, offset);
    }

    static void arraycopy(byte[] src, byte[] dst, int dindex) {
        System.arraycopy(src,0,dst,dindex,src.length);
    }
}
