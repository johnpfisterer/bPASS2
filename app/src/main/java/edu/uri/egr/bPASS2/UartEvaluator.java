package edu.uri.egr.bPASS2;

import java.nio.ByteBuffer;

import edu.uri.egr.hermesble.evaluator.ByteValueEvaluator;

public class UartEvaluator extends ByteValueEvaluator<UartEvent> {
    @Override
    public UartEvent evaluate(byte[] value) {
           byte[] data = new byte[]{value[1], value[2]};
           return new UartEvent(value[0], (int) ByteBuffer.wrap(data).getChar());
    }
}
