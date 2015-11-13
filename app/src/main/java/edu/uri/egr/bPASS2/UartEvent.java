package edu.uri.egr.bPASS2;

public class UartEvent {
    public byte type;
    public int data;

    public UartEvent(byte type, int data) {
        this.type = type;
        this.data = data;
    }
}
