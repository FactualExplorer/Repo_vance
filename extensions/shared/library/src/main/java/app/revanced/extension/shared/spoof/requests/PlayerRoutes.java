package app.revanced.extension.shared.spoof.requests;

import org.json.JSONException;
import org.json.JSONObject;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.requests.Requester;
import app.revanced.extension.shared.requests.Route;
import app.revanced.extension.shared.settings.AppLanguage;
import app.revanced.extension.shared.spoof.ClientType;
import app.revanced.extension.shared.spoof.SpoofVideoStreamsPatch;

final class PlayerRoutes {
    static final Route.CompiledRoute GET_PLAYER_STREAMING_DATA = new Route(
            Route.Method.POST,
            "player" +
                    "?fields=playabilityStatus,streamingData" +
                    "&alt=proto"
    ).compile();

    static final Route.CompiledRoute GET_REEL_STREAMING_DATA = new Route(
            Route.Method.POST,
            "reel/reel_item_watch" +
                    "?fields=playerResponse.playabilityStatus,playerResponse.streamingData" +
                    "&alt=proto"
    ).compile();

    private static final String YT_API_URL = "https://youtubei.googleapis.com/youtubei/v1/";
    private static final String YT_WEB_API_URL = "https://www.youtube.com/youtubei/v1/";
    private static final Route.CompiledRoute GET_VISITOR_DATA = new Route(
            Route.Method.POST, "visitor_id?prettyPrint=false").compile();

    /**
     * TCP connection and HTTP read timeout
     */
    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 10 * 1000; // 10 Seconds.

    private PlayerRoutes() {
    }

    static String createInnertubeBody(ClientType clientType, String videoId) {
        return createInnertubeBody(clientType, videoId, null);
    }

    static String createInnertubeBody(ClientType clientType, String videoId,
                                      @Nullable String visitorData) {
        JSONObject innerTubeBody = new JSONObject();

        try {
            JSONObject context = createInnertubeContext(clientType);
            if (visitorData != null) {
                context.getJSONObject("client").put("visitorData", visitorData);
            }
            innerTubeBody.put("context", context);

            if (clientType.usePlayerEndpoint) {
                innerTubeBody.put("contentCheckOk", true);
                innerTubeBody.put("racyCheckOk", true);
                innerTubeBody.put("videoId", videoId);
            } else {
                JSONObject playerRequest = new JSONObject();
                playerRequest.put("contentCheckOk", true);
                playerRequest.put("racyCheckOk", true);
                playerRequest.put("videoId", videoId);
                innerTubeBody.put("playerRequest", playerRequest);
                innerTubeBody.put("disablePlayerResponse", false);
            }
        } catch (JSONException e) {
            Logger.printException(() -> "Failed to create innerTubeBody", e);
        }

        return innerTubeBody.toString();
    }

    private static JSONObject createInnertubeContext(ClientType clientType) throws JSONException {
        AppLanguage language = SpoofVideoStreamsPatch.getLanguageOverride();
        if (language == null) language = AppLanguage.DEFAULT;
        Locale streamLocale = language.getLocale();

        JSONObject client = new JSONObject();
        client.put("deviceMake", clientType.deviceMake);
        client.put("deviceModel", clientType.deviceModel);
        client.put("clientName", clientType.clientName);
        client.put("clientVersion", clientType.clientVersion);
        client.put("osName", clientType.osName);
        client.put("osVersion", clientType.osVersion);
        if (clientType.androidSdkVersion != null) {
            client.put("androidSdkVersion", clientType.androidSdkVersion);
        }
        client.put("hl", streamLocale.getLanguage());
        client.put("gl", streamLocale.getCountry());
        if (clientType == ClientType.VISIONOS) {
            client.put("clientScreen", "WATCH");
            client.put("platform", "MOBILE");
            client.put("utcOffsetMinutes", 0);
        }
        return new JSONObject().put("client", client);
    }

    /**
     * Obtain a visitor identity from YouTube for the requested client. An Android
     * visitor header is not a substitute for this visionOS bootstrap. Keep it local
     * to one fetch so concurrent videos cannot mix visitor identities.
     */
    static String fetchVisitorData(ClientType clientType) throws IOException {
        HttpURLConnection connection = Requester.getConnectionFromCompiledRoute(YT_WEB_API_URL, GET_VISITOR_DATA);
        try {
            configureConnection(connection, clientType);
            byte[] body = new JSONObject().put("context", createInnertubeContext(clientType))
                    .toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (var output = connection.getOutputStream()) {
                output.write(body);
            }
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Visitor request failed: HTTP " + responseCode);
            }
            JSONObject responseContext = Requester.parseJSONObject(connection).optJSONObject("responseContext");
            String visitorData = responseContext == null ? "" : responseContext.optString("visitorData", "");
            if (visitorData.isEmpty()) {
                throw new IOException("Visitor response has no visitor data");
            }
            return visitorData;
        } catch (JSONException ex) {
            throw new IOException("Invalid visitor response", ex);
        } finally {
            connection.disconnect();
        }
    }

    @SuppressWarnings("SameParameterValue")
    static HttpURLConnection getPlayerResponseConnectionFromRoute(Route.CompiledRoute route, ClientType clientType) throws IOException {
        var connection = Requester.getConnectionFromCompiledRoute(YT_API_URL, route);
        configureConnection(connection, clientType);
        return connection;
    }

    private static void configureConnection(HttpURLConnection connection, ClientType clientType) {
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", clientType.userAgent);
        if (clientType == ClientType.VISIONOS) {
            // The client identity is already in the body, as in NewPipe's request flow.
            connection.setRequestProperty("X-Goog-Api-Format-Version", "2");
        } else {
            // Match the client identity used by the upstream Reel playback fix.
            connection.setRequestProperty("X-YouTube-Client-Name", clientType.clientName);
            connection.setRequestProperty("X-YouTube-Client-Version", clientType.clientVersion);
        }

        connection.setUseCaches(false);
        connection.setDoOutput(true);

        connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
    }
}
