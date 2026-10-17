package app.revanced.extension.shared.spoof.requests;

import app.revanced.extension.shared.innertube.PlayerResponseOuterClass;
import app.revanced.extension.shared.spoof.ClientType;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Opt-in network smoke test. Uses production visitor, player and protobuf code. */
public final class LivePlaybackProbe {
    public static void main(String[] args) throws Exception {
        String videoId = args[0];
        if (!videoId.matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException("Expected a video ID");
        int delay = Integer.parseInt(args[1]);
        if (delay < 0 || delay > 120) throw new IllegalArgumentException("Delay must be 0 to 120 seconds");
        StreamingDataRequest.setClientOrderToUse(List.of(ClientType.VISIONOS), ClientType.VISIONOS, true);
        StreamingDataRequest.fetchRequest(videoId, Map.of());
        byte[] buffer = StreamingDataRequest.getRequestForVideoId(videoId).getStream();
        if (buffer == null) throw new AssertionError("Live visionOS request returned no usable streams");
        var formats = PlayerResponseOuterClass.PlayerResponse.parseFrom(buffer).getStreamingData().getAdaptiveFormatsList();
        System.out.println("LIVE: production visitor/player/protobuf flow returned " + formats.size() + " adaptive formats");
        while (delay > 0) {
            int step = Math.min(30, delay);
            System.out.println("LIVE: waiting " + step + " seconds before media probes");
            Thread.sleep(step * 1000L);
            delay -= step;
        }
        for (String quality : List.of("720p", "1440p")) {
            var format = formats.stream().filter(f -> f.getQualityLabel().startsWith(quality) && !f.getUrl().isEmpty())
                    .findFirst().orElseThrow(() -> new AssertionError("No " + quality + " format for test video"));
            URI uri = URI.create(format.getUrl());
            if (!uri.getScheme().equals("https") || !uri.getHost().endsWith(".googlevideo.com")) {
                throw new AssertionError("Unexpected media URL host");
            }
            Map<String, String> params = new HashMap<>();
            for (String part : uri.getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length == 2) params.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            long length = Long.parseLong(params.get("clen"));
            double duration = Double.parseDouble(params.get("dur"));
            if (duration <= 90) throw new AssertionError("Choose a test video longer than 90 seconds");
            // Byte offsets estimate a media position; this does not decode or play video.
            long laterOffset = (long) (length * 75.0 / duration);
            for (long offset : new long[]{0, laterOffset}) {
                HttpURLConnection connection = (HttpURLConnection) URI.create(
                        format.getUrl() + "&range=" + offset + "-" + (offset + 1023)).toURL().openConnection();
                try {
                    connection.setConnectTimeout(10_000);
                    connection.setReadTimeout(10_000);
                    // Match the app's media user agent, rather than the visionOS API user agent.
                    connection.setRequestProperty("User-Agent", "com.google.android.youtube/20.40.45 (Linux; U; Android 15)");
                    int code = connection.getResponseCode();
                    if (code != 200 && code != 206) throw new AssertionError(quality + " media range HTTP " + code);
                    try (var input = connection.getInputStream()) {
                        if (input.readNBytes(1024).length != 1024) throw new AssertionError("Incomplete media range");
                    }
                    long responseLength = connection.getContentLengthLong();
                    if (responseLength != 1024) throw new AssertionError("Server did not confirm the requested range length");
                    System.out.println("LIVE PASS: " + format.getQualityLabel() + " offset=" + offset
                            + " HTTP=" + code + " bytes=" + responseLength);
                } finally {
                    connection.disconnect();
                }
            }
        }
    }
}
