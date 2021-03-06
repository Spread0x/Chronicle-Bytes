package net.openhft.chronicle.bytes;

import org.jetbrains.annotations.NotNull;

class MyNested implements BytesMarshallable {
    MyByteable byteable;
    MyScalars scalars;

    public MyNested() {
    }

    public MyNested(MyByteable byteable, MyScalars scalars) {
        this.byteable = byteable;
        this.scalars = scalars;
    }

    @NotNull
    @Override
    public String toString() {
        return "MyNested{" +
                "byteable=" + byteable +
                ", scalars=" + scalars +
                '}';
    }
}
