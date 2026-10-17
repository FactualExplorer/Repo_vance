package app.revanced.extension.shared.spoof.requests;

import app.revanced.extension.shared.innertube.PlayerResponseOuterClass.*;
import app.revanced.extension.shared.innertube.ReelItemWatchResponseOuterClass.ReelItemWatchResponse;
import app.revanced.extension.shared.spoof.ClientType;
import com.google.protobuf.CodedOutputStream;
import org.json.JSONObject;

import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.util.*;

/** Tests the production request and response code using in-memory HTTP responses. */
public final class PlaybackRegressionTest {
    private static final List<Format> MIXED_FORMATS = List.of(
            video("avc1", "360p"), video("avc1", "720p"), video("avc1", "1080p"),
            video("vp9", "480p"), Format.newBuilder().setMimeType("audio/webm; codecs=\"opus\"").build());
    private static final Deque<byte[]> HTTP_RESPONSES = new ArrayDeque<>();
    private static final List<FakeConnection> CONNECTIONS = new ArrayList<>();
    private static int passed;

    public static void main(String[] args) throws Exception {
        // Fail closed: no test is allowed to contact a real HTTPS service.
        URL.setURLStreamHandlerFactory(protocol -> protocol.equals("https") ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                if (HTTP_RESPONSES.isEmpty()) throw new AssertionError("Unexpected HTTP request: " + url);
                FakeConnection connection = new FakeConnection(url, HTTP_RESPONSES.removeFirst());
                CONNECTIONS.add(connection);
                return connection;
            }
        } : null);

        check("AVC resolutions remain selectable when VP9 only offers 480p", () -> {
            var result = filter(MIXED_FORMATS, true);
            require(result.getAdaptiveFormatsCount() == 4, "Expected three AVC qualities plus audio");
            require(result.getAdaptiveFormatsList().stream().noneMatch(f -> f.getMimeType().contains("vp9")),
                    "Single-quality VP9 must not hide the AVC quality selector");
            require(result.getAdaptiveFormatsList().get(3).equals(MIXED_FORMATS.get(4)), "Audio changed");
        });
        check("YouTube Music keeps its original formats", () ->
                require(filter(MIXED_FORMATS, false).getAdaptiveFormatsList().equals(MIXED_FORMATS), "Music formats changed"));
        check("Multiple VP9 qualities remain available", () -> {
            var formats = new ArrayList<>(MIXED_FORMATS);
            formats.add(video("vp9", "720p"));
            require(filter(formats, true).getAdaptiveFormatsList().equals(formats), "Complete VP9 ladder removed");
        });
        check("VP9-only videos remain playable", () -> {
            var formats = List.of(video("vp9", "480p"));
            require(filter(formats, true).getAdaptiveFormatsList().equals(formats), "Only video format removed");
        });
        check("Duplicate AVC formats do not count as different qualities", () -> {
            var formats = List.of(video("avc1", "720p"), video("avc1", "720p"), video("vp9", "480p"));
            require(filter(formats, true).getAdaptiveFormatsList().equals(formats), "Duplicate quality triggered filtering");
        });
        check("Missing quality labels do not trigger filtering", () -> {
            var formats = List.of(video("avc1", ""), video("avc1", ""), video("vp9", "480p"));
            require(filter(formats, true).getAdaptiveFormatsList().equals(formats), "Unlabelled formats changed");
        });
        check("AV1 and unknown protobuf metadata survive filtering", () -> {
            var formats = new ArrayList<>(MIXED_FORMATS);
            byte[] marker = field(1000, "unknown-format-metadata".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Format av1 = Format.parseFrom(concat(video("av01", "2160p").toByteArray(), marker));
            formats.add(av1);
            var result = filter(formats, true);
            require(result.getAdaptiveFormatsList().contains(av1), "AV1 or unknown format fields lost");
            require(result.getServerAbrStreamingUrl().equals("https://example.invalid/sabr"), "Other streaming fields lost");
        });
        check("Player status and stream data survive either wire order", () -> {
            byte[] status = field(2, PlayabilityStatus.newBuilder().setStatus(Status.OK).setReason("fixture").build().toByteArray());
            byte[] data = field(4, streams(MIXED_FORMATS).toByteArray());
            for (byte[] bytes : List.of(concat(status, data), concat(data, status))) {
                var response = PlayerResponse.parseFrom(bytes);
                require(response.hasStreamingData() && response.hasPlayabilityStatus(), "A valid protobuf field was discarded");
                require(parse(ClientType.ANDROID_VR_1_43_32, bytes) != null, "Playable response rejected");
            }
        });
        check("Error status cannot be hidden by streaming data", () -> {
            byte[] status = field(2, PlayabilityStatus.newBuilder().setStatus(Status.LOGIN_REQUIRED).build().toByteArray());
            byte[] data = field(4, streams(MIXED_FORMATS).toByteArray());
            for (byte[] bytes : List.of(concat(status, data), concat(data, status))) {
                require(parse(ClientType.ANDROID_VR_1_43_32, bytes) == null, "Unplayable response accepted");
            }
        });
        check("Empty, malformed and non-adaptive responses are rejected", () -> {
            require(parse(ClientType.ANDROID_VR_1_43_32, new byte[0]) == null, "Empty response accepted");
            require(parse(ClientType.ANDROID_VR_1_43_32, new byte[]{(byte) 0xff}) == null, "Malformed response accepted");
            require(parse(ClientType.ANDROID_VR_1_43_32, response(List.of()).toByteArray()) == null, "Empty formats accepted");
        });
        check("Reel responses use the nested player response", () -> {
            byte[] bytes = reel(response(MIXED_FORMATS));
            require(parse(ClientType.ANDROID_REEL_NO_AUTH, bytes) != null, "Reel response not parsed");
            var body = new JSONObject(PlayerRoutes.createInnertubeBody(ClientType.ANDROID_REEL_NO_AUTH, "test-video"));
            require(body.getJSONObject("playerRequest").getString("videoId").equals("test-video"), "Wrong Reel request body");
        });
        check("Unauthenticated Reel omits the access token even while signed in", () -> {
            StreamingDataRequest.setClientOrderToUse(List.of(ClientType.ANDROID_REEL_NO_AUTH), ClientType.ANDROID_REEL_NO_AUTH, true);
            HTTP_RESPONSES.add(reel(response(MIXED_FORMATS)));
            StreamingDataRequest.fetchRequest("no-auth-test", Map.of("Authorization", "test-access-token"));
            require(StreamingDataRequest.getRequestForVideoId("no-auth-test").getStream() != null, "No-auth request failed");
            require(lastConnection().getRequestProperty("Authorization") == null, "No-auth client forwarded Authorization");
        });
        check("Unauthenticated Reel also works with no login headers", () -> {
            HTTP_RESPONSES.add(reel(response(MIXED_FORMATS)));
            StreamingDataRequest.fetchRequest("logged-out-test", Map.of());
            require(StreamingDataRequest.getRequestForVideoId("logged-out-test").getStream() != null, "Logged-out request skipped");
        });
        check("Authenticated Reel retains explicit login support", () -> {
            StreamingDataRequest.setClientOrderToUse(List.of(ClientType.ANDROID_REEL), ClientType.ANDROID_REEL, true);
            HTTP_RESPONSES.add(reel(response(MIXED_FORMATS)));
            StreamingDataRequest.fetchRequest("auth-test", Map.of("Authorization", "test-access-token"));
            require(StreamingDataRequest.getRequestForVideoId("auth-test").getStream() != null, "Authenticated request failed");
            require("test-access-token".equals(lastConnection().getRequestProperty("Authorization")), "Authentication was dropped");
        });
        check("Unplayable preferred client falls back to another client", () -> {
            StreamingDataRequest.setClientOrderToUse(List.of(ClientType.ANDROID_VR_1_43_32), ClientType.ANDROID_REEL_NO_AUTH, true);
            HTTP_RESPONSES.add(reel(PlayerResponse.newBuilder().setPlayabilityStatus(
                    PlayabilityStatus.newBuilder().setStatus(Status.UNPLAYABLE)).build()));
            HTTP_RESPONSES.add(response(MIXED_FORMATS).toByteArray());
            int before = CONNECTIONS.size();
            StreamingDataRequest.fetchRequest("fallback-test", Map.of());
            require(StreamingDataRequest.getRequestForVideoId("fallback-test").getStream() != null, "Fallback failed");
            require(CONNECTIONS.size() == before + 2, "Wrong number of attempts");
        });
        check("visionOS obtains and uses its own visitor identity", () -> {
            StreamingDataRequest.setClientOrderToUse(List.of(ClientType.VISIONOS), ClientType.VISIONOS, true);
            int before = CONNECTIONS.size();
            HTTP_RESPONSES.add(visitor("vision-visitor-one"));
            HTTP_RESPONSES.add(response(MIXED_FORMATS).toByteArray());
            StreamingDataRequest.fetchRequest("vision-test", Map.of(
                    "Authorization", "test-access-token", "X-Goog-Visitor-Id", "android-visitor",
                    "X-GOOG-API-FORMAT-VERSION", "1"));
            require(StreamingDataRequest.getRequestForVideoId("vision-test").getStream() != null, "visionOS fetch failed");
            require(CONNECTIONS.size() == before + 2, "Missing visitor bootstrap");
            FakeConnection bootstrap = CONNECTIONS.get(before);
            require(bootstrap.getURL().getHost().equals("www.youtube.com"), "Wrong visitor service");
            require(bootstrap.getURL().getPath().endsWith("/visitor_id"), "Wrong bootstrap endpoint");
            require(bootstrap.disconnected, "Visitor connection was not released");
            for (FakeConnection c : CONNECTIONS.subList(before, CONNECTIONS.size())) {
                require(c.getRequestProperty("Authorization") == null, "Android authentication leaked into visionOS");
                require(c.getRequestProperty("X-Goog-Visitor-Id") == null, "Android visitor leaked into visionOS");
                require("2".equals(c.getRequestProperty("X-Goog-Api-Format-Version")), "Wrong API format");
            }
            var client = new JSONObject(lastConnection().request.toString(java.nio.charset.StandardCharsets.UTF_8))
                    .getJSONObject("context").getJSONObject("client");
            require(client.getString("visitorData").equals("vision-visitor-one"), "Bootstrap identity not used");
            require(client.getString("platform").equals("MOBILE"), "Client platform missing");
            require(client.getString("clientScreen").equals("WATCH"), "Client screen missing");
        });
        check("Visitor identities are not reused across video fetches", () -> {
            HTTP_RESPONSES.add(visitor("vision-visitor-two"));
            HTTP_RESPONSES.add(response(MIXED_FORMATS).toByteArray());
            StreamingDataRequest.fetchRequest("vision-second-test", Map.of());
            require(StreamingDataRequest.getRequestForVideoId("vision-second-test").getStream() != null, "Second fetch failed");
            var client = new JSONObject(lastConnection().request.toString(java.nio.charset.StandardCharsets.UTF_8))
                    .getJSONObject("context").getJSONObject("client");
            require(client.getString("visitorData").equals("vision-visitor-two"), "Visitor from another video reused");
        });
        check("Missing visitor data falls back without sending an invalid visionOS player request", () -> {
            StreamingDataRequest.setClientOrderToUse(List.of(ClientType.ANDROID_VR_1_43_32), ClientType.VISIONOS, true);
            int before = CONNECTIONS.size();
            HTTP_RESPONSES.add("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            HTTP_RESPONSES.add(response(MIXED_FORMATS).toByteArray());
            StreamingDataRequest.fetchRequest("missing-visitor-test", Map.of());
            require(StreamingDataRequest.getRequestForVideoId("missing-visitor-test").getStream() != null, "Fallback failed");
            require(CONNECTIONS.size() == before + 2, "An invalid visionOS player request was sent");
            require(lastConnection().getRequestProperty("User-Agent").equals(ClientType.ANDROID_VR_1_43_32.userAgent), "Wrong fallback client");
        });
        check("Malformed visitor response releases its connection", () -> {
            StreamingDataRequest.setClientOrderToUse(List.of(ClientType.VISIONOS), ClientType.VISIONOS, true);
            HTTP_RESPONSES.add("not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StreamingDataRequest.fetchRequest("bad-visitor-test", Map.of());
            require(StreamingDataRequest.getRequestForVideoId("bad-visitor-test").getStream() == null, "Malformed bootstrap accepted");
            require(lastConnection().disconnected, "Failed bootstrap connection leaked");
        });
        require(HTTP_RESPONSES.isEmpty(), "Unused HTTP fixtures");
        System.out.println("Passed " + passed + " playback regression checks.");
    }

    private static StreamingData filter(List<Format> formats, boolean enabled) throws Exception {
        StreamingDataRequest.setClientOrderToUse(List.of(ClientType.ANDROID_REEL_NO_AUTH), ClientType.ANDROID_REEL_NO_AUTH, enabled);
        byte[] bytes = parse(ClientType.ANDROID_VR_1_43_32, response(formats).toByteArray());
        require(bytes != null, "Response rejected");
        return PlayerResponse.parseFrom(bytes).getStreamingData();
    }

    private static byte[] parse(ClientType client, byte[] bytes) throws Exception {
        Method parser = StreamingDataRequest.class.getDeclaredMethod("buildPlayerResponseBuffer", ClientType.class, HttpURLConnection.class);
        parser.setAccessible(true);
        return (byte[]) parser.invoke(null, client, new FakeConnection(new URL("https://example.invalid"), bytes));
    }

    private static Format video(String codec, String quality) {
        return Format.newBuilder().setMimeType("video/" + (codec.equals("avc1") ? "mp4" : "webm")
                + "; codecs=\"" + codec + "\"").setQualityLabel(quality).setUrl("https://example.invalid/video").build();
    }

    private static StreamingData streams(List<Format> formats) {
        return StreamingData.newBuilder().addAllAdaptiveFormats(formats).setServerAbrStreamingUrl("https://example.invalid/sabr").build();
    }

    private static PlayerResponse response(List<Format> formats) {
        return PlayerResponse.newBuilder().setPlayabilityStatus(PlayabilityStatus.newBuilder().setStatus(Status.OK))
                .setStreamingData(streams(formats)).build();
    }

    private static byte[] reel(PlayerResponse response) {
        return ReelItemWatchResponse.newBuilder().setPlayerResponse(response).build().toByteArray();
    }

    private static byte[] visitor(String value) {
        return new JSONObject().put("responseContext", new JSONObject().put("visitorData", value))
                .toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] field(int number, byte[] value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var coded = CodedOutputStream.newInstance(bytes);
        coded.writeByteArray(number, value);
        coded.flush();
        return bytes.toByteArray();
    }

    private static byte[] concat(byte[] first, byte[] second) throws IOException {
        var bytes = new ByteArrayOutputStream();
        bytes.write(first);
        bytes.write(second);
        return bytes.toByteArray();
    }

    private static FakeConnection lastConnection() { return CONNECTIONS.get(CONNECTIONS.size() - 1); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void check(String name, CheckedRunnable test) throws Exception {
        test.run();
        passed++;
        System.out.println("PASS " + name);
    }
    private interface CheckedRunnable { void run() throws Exception; }

    private static final class FakeConnection extends HttpURLConnection {
        private final byte[] response;
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();
        private boolean disconnected;
        FakeConnection(URL url, byte[] response) { super(url); this.response = response; }
        @Override public int getContentLength() { return response.length; }
        @Override public int getResponseCode() { return 200; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response); }
        @Override public OutputStream getOutputStream() { return request; }
        @Override public void connect() {}
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
    }
}
