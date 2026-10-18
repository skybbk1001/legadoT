package me.ag2s.base;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * 读取FileChannel的工具类
 */
@SuppressWarnings("unused")
public final class PfdHelper {

    /**
     * 读取基本类型的buffer
     */
    private static final byte[] readBuffer = new byte[8];

    public static void seek(FileChannel pfd, long pos) throws IOException {
        pfd.position(pos);
    }

    public static long getFilePointer(FileChannel pfd) throws IOException {
        return pfd.position();
    }

    public static long length(FileChannel pfd) throws IOException {
        return pfd.size();
    }

    private static int readBytes(FileChannel pfd, byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        return pfd.read(ByteBuffer.wrap(b, off, len));
    }

    public static int read(FileChannel pfd) throws IOException {
        return (read(pfd, readBuffer, 0, 1) != -1) ? readBuffer[0] & 0xff : -1;
    }

    public static int read(FileChannel pfd, byte[] b, int off, int len) throws IOException {
        return readBytes(pfd, b, off, len);
    }

    public static int read(FileChannel pfd, byte[] b) throws IOException {
        return readBytes(pfd, b, 0, b.length);
    }

    public static void readFully(FileChannel pfd, byte[] b) throws IOException {
        readFully(pfd, b, 0, b.length);
    }

    public static void readFully(FileChannel pfd, byte[] b, int off, int len) throws IOException {
        int n = 0;
        do {
            int count = read(pfd, b, off + n, len - n);
            if (count < 0)
                throw new EOFException();
            n += count;
        } while (n < len);
    }


    public static int skipBytes(FileChannel pfd, int n) throws IOException {
        long pos;
        long len;
        long newpos;

        if (n <= 0) {
            return 0;
        }
        pos = getFilePointer(pfd);
        len = length(pfd);
        newpos = pos + n;
        if (newpos > len) {
            newpos = len;
        }
        seek(pfd, newpos);

        /* return the actual number of bytes skipped */
        return (int) (newpos - pos);
    }
}
