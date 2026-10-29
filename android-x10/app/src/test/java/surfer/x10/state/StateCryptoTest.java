package surfer.x10.state;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StateCryptoTest {
    @Test
    public void hexRoundTrip() {
        byte[] b = {0, 1, (byte) 0x7f, (byte) 0x80, (byte) 0xff};
        assertEquals("00017f80ff", StateCrypto.toHex(b));
        assertArrayEquals(b, StateCrypto.fromHex("00017f80ff"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBadHex() {
        StateCrypto.fromHex("0g");
    }
}
