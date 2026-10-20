package dev.cairn;

import java.io.IOException;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Objects;

/** Incremental UTF-8 log reader, retaining split code points and handling file truncation/rotation. */
public final class LogCursor {
    private final Path path;
    private long position;
    private Object fileKey;
    private byte[] carry=new byte[0];
    private final CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
    public LogCursor(Path path,long position) throws IOException {
        this.path=path; this.position=position;
        if (Files.exists(path)) fileKey=Files.readAttributes(path,BasicFileAttributes.class).fileKey();
    }
    public String read() throws IOException {
        if (!Files.isRegularFile(path)) return "";
        BasicFileAttributes attrs=Files.readAttributes(path,BasicFileAttributes.class);
        if (attrs.size() < position || fileKey != null && !Objects.equals(fileKey,attrs.fileKey())) {
            position=0; carry=new byte[0]; decoder.reset();
        }
        fileKey=attrs.fileKey();
        int available=(int)Math.min(1024*1024,Math.max(0,attrs.size()-position));
        if (available == 0) return "";
        ByteBuffer bytes=ByteBuffer.allocate(carry.length+available); bytes.put(carry);
        try (var channel=Files.newByteChannel(path)) {
            channel.position(position); int read=0,n;
            while (bytes.hasRemaining() && (n=channel.read(bytes)) > 0) read+=n;
            position+=read;
        }
        bytes.flip(); CharBuffer chars=CharBuffer.allocate(bytes.remaining()+4);
        CoderResult result=decoder.decode(bytes,chars,false);
        if (result.isError()) result.throwException();
        carry=new byte[bytes.remaining()]; bytes.get(carry);
        chars.flip(); return chars.toString();
    }
    public long position() { return position; }
}
