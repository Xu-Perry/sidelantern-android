package com.limelight;

import com.limelight.nvstream.http.StreamModesResponse;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StreamModesResponseTest {

    private static final String VALID_XML =
            "<root status_code=\"200\" version=\"1\" manual_video_version=\"1\">"
                    + "<mode id=\"primary\" available=\"1\" reason=\"\"/>"
                    + "<mode id=\"secondary\" available=\"0\" reason=\"secondary_display_unavailable\"/>"
                    + "<mode id=\"extend\" available=\"1\" reason=\"\"/>"
                    + "</root>";

    @Test
    public void parsesValidV1AndManualVideoVersion() {
        StreamModesResponse resp = new StreamModesResponse(200, VALID_XML);
        assertTrue(resp.supportsDisplayModes());
        assertTrue(resp.supportsManualVideo());
        assertFalse(resp.isLegacyHost());

        assertTrue(resp.isModeAvailable(StreamModesResponse.MODE_PRIMARY));
        assertEquals("", resp.reasonForMode(StreamModesResponse.MODE_PRIMARY));

        assertFalse(resp.isModeAvailable(StreamModesResponse.MODE_SECONDARY));
        assertEquals("secondary_display_unavailable", resp.reasonForMode(StreamModesResponse.MODE_SECONDARY));

        assertTrue(resp.isModeAvailable(StreamModesResponse.MODE_EXTEND));
    }

    @Test
    public void rejectsMissingOrDuplicateModesOrBadAttributes() {
        String missingExtend =
                "<root status_code=\"200\" version=\"1\">"
                        + "<mode id=\"primary\" available=\"1\" reason=\"\"/>"
                        + "<mode id=\"secondary\" available=\"1\" reason=\"\"/>"
                        + "</root>";
        assertFalse(new StreamModesResponse(200, missingExtend).supportsDisplayModes());

        String duplicatePrimary =
                "<root status_code=\"200\" version=\"1\">"
                        + "<mode id=\"primary\" available=\"1\" reason=\"\"/>"
                        + "<mode id=\"primary\" available=\"1\" reason=\"\"/>"
                        + "<mode id=\"secondary\" available=\"1\" reason=\"\"/>"
                        + "<mode id=\"extend\" available=\"1\" reason=\"\"/>"
                        + "</root>";
        assertFalse(new StreamModesResponse(200, duplicatePrimary).supportsDisplayModes());

        String availableWithNonEmptyReason =
                "<root status_code=\"200\" version=\"1\">"
                        + "<mode id=\"primary\" available=\"1\" reason=\"unexpected\"/>"
                        + "<mode id=\"secondary\" available=\"1\" reason=\"\"/>"
                        + "<mode id=\"extend\" available=\"1\" reason=\"\"/>"
                        + "</root>";
        assertFalse(new StreamModesResponse(200, availableWithNonEmptyReason).supportsDisplayModes());

        String unsupportedVersion =
                "<root status_code=\"200\" version=\"2\">"
                        + "<mode id=\"primary\" available=\"1\" reason=\"\"/>"
                        + "<mode id=\"secondary\" available=\"1\" reason=\"\"/>"
                        + "<mode id=\"extend\" available=\"1\" reason=\"\"/>"
                        + "</root>";
        assertFalse(new StreamModesResponse(200, unsupportedVersion).supportsDisplayModes());
    }

    @Test
    public void identifiesLegacy404And501Only() {
        assertTrue(new StreamModesResponse(404, "").isLegacyHost());
        assertTrue(new StreamModesResponse(501, "").isLegacyHost());
        assertTrue(new StreamModesResponse(200, "<root status_code=\"404\"/>").isLegacyHost());
        assertFalse(new StreamModesResponse(500, "").isLegacyHost());
        assertFalse(new StreamModesResponse(401, "").isLegacyHost());
    }

    @Test
    public void mapsAllFailureReasonsToDistinctResources() {
        assertEquals(R.string.sl_target_error_unknown_mode,
                StreamModesResponse.getFailureMessageResId("unknown_mode"));
        assertEquals(R.string.sl_target_error_capture_busy,
                StreamModesResponse.getFailureMessageResId("capture_busy"));
        assertEquals(R.string.sl_target_error_primary_display_unavailable,
                StreamModesResponse.getFailureMessageResId("primary_display_unavailable"));
        assertEquals(R.string.sl_target_error_secondary_display_unavailable,
                StreamModesResponse.getFailureMessageResId("secondary_display_unavailable"));
        assertEquals(R.string.sl_target_error_other_displays_active,
                StreamModesResponse.getFailureMessageResId("other_displays_active"));
        assertEquals(R.string.sl_target_error_display_preparation_failed,
                StreamModesResponse.getFailureMessageResId("display_preparation_failed"));
        assertEquals(R.string.sl_target_error_unsupported_platform,
                StreamModesResponse.getFailureMessageResId("unsupported_platform"));
        assertEquals(R.string.sl_target_error_unavailable,
                StreamModesResponse.getFailureMessageResId("something_else"));
    }
}
