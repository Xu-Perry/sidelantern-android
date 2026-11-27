package com.limelight;

import com.limelight.utils.DeviceNameHelper;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class DeviceNameHelperTest {

    @Test
    public void resolvePrefersCustomSystemName() {
        assertEquals("Pixel 9 Pro",
                DeviceNameHelper.resolveDeviceName("  Pixel 9 Pro  ", "Google", "Pixel 9 Pro", "abcdef123456"));
    }

    @Test
    public void resolveFallsBackToManufacturerAndModelWhenSystemNameGeneric() {
        assertEquals("Samsung SM-S928U",
                DeviceNameHelper.resolveDeviceName("Android", "Samsung", "SM-S928U", "abcdef123456"));
        // Avoid duplicating manufacturer when model already starts with it
        assertEquals("Google Pixel 9",
                DeviceNameHelper.resolveDeviceName("unknown", "Google", "Google Pixel 9", "abcdef123456"));
    }

    @Test
    public void resolveFallsBackToAndroidShortIdWhenAllGeneric() {
        assertEquals("Android-ABCDEF",
                DeviceNameHelper.resolveDeviceName("generic", "unknown", "emulator", "abcdef999999"));
        assertEquals("Android-DEVICE",
                DeviceNameHelper.resolveDeviceName(null, null, null, null));
    }

    @Test
    public void percentEncodeRfc3986PreservesUnreservedAndEncodesSpecials() {
        assertEquals("Alpha-09_~.", DeviceNameHelper.percentEncodeQueryValue("Alpha-09_~."));
        assertEquals("My%20Phone%20%2B%201%26a%3Db%23c%3Fd%25e%2Ff",
                DeviceNameHelper.percentEncodeQueryValue("My Phone + 1&a=b#c?d%e/f"));
        assertEquals("%E6%88%91%E7%9A%84%E6%89%8B%E6%9C%BA",
                DeviceNameHelper.percentEncodeQueryValue("我的手机"));
        // Must never leave raw spaces or '+' unencoded
        String encoded = DeviceNameHelper.percentEncodeQueryValue("A + B");
        assertFalse(encoded.contains(" "));
        assertFalse(encoded.contains("+"));
        assertEquals("A%20%2B%20B", encoded);
    }
}
