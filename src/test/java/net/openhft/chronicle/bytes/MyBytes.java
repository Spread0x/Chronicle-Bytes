package net.openhft.chronicle.bytes;

import java.io.Closeable;

@SuppressWarnings("rawtypes")
class MyBytes implements BytesMarshallable, Closeable {
    Bytes bytes1;
    Bytes bytes2;

    public MyBytes() {
    }

    public MyBytes(Bytes bytes1, Bytes bytes2) {
        this.bytes1 = bytes1;
        this.bytes2 = bytes2;
    }

    @Override
    public void close() {
        if (bytes1 != null) bytes1.releaseLast();
        if (bytes2 != null) bytes2.releaseLast();
    }

    @Override
    public String toString() {
        return "MyBytes{" +
                "bytes1=" + bytes1 +
                ", bytes2=" + bytes2 +
                '}';
    }
}
