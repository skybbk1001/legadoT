package me.ag2s.base;



import java.io.IOException;

public class ThrowableUtils {

    public static 
    IOException rethrowAsIOException(Throwable throwable) throws IOException {
        IOException newException = new IOException(throwable.getMessage(), throwable);
        throw newException;
    }
}
