import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Quiz Leaderboard System
 * -----------------------
 * Bajaj Finserv Health — JAVA Qualifier — SRM
 *
 * Flow:
 * 1. Poll the validator API 10 times (poll 0–9) with a 5-second delay between
 * each.
 * 2. Collect all API responses.
 * 3. Deduplicate events using the composite key (roundId + participant).
 * 4. Aggregate scores per participant.
 * 5. Generate a leaderboard sorted by totalScore (descending).
 * 6. Compute the total score across all users.
 * 7. Submit the leaderboard once.
 */
public class QuizLeaderboard {

    // Configuration
    private static final String REG_NO = "RA2311003011950";
    private static final String BASE_URL = "https://devapigw.vidalhealthtpa.com/srm-quiz-task";
    private static final int POLL_COUNT = 10;
    private static final int POLL_DELAY_MS = 5000; // 5 seconds between polls

    // Simple JSON Helpers (no external libraries)

    /**
     * Perform an HTTP GET request and return the response body as a String.
     */
    private static String httpGet(String urlStr) throws Exception {
        URL url = new URI(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");

        int status = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream(),
                        StandardCharsets.UTF_8));

        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        conn.disconnect();
        return response.toString();
    }

    /**
     * Perform an HTTP POST request with a JSON body and return the response body.
     */
    private static String httpPost(String urlStr, String jsonBody) throws Exception {
        URL url = new URI(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream(),
                        StandardCharsets.UTF_8));

        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        conn.disconnect();
        return response.toString();
    }

    /**
     * Extract events array from a JSON response string.
     * Parses: {"events": [{"roundId":"R1","participant":"Alice","score":10}, ...]}
     * Returns a list of maps with keys: roundId, participant, score.
     */
    private static List<Map<String, Object>> parseEvents(String json) {
        List<Map<String, Object>> events = new ArrayList<>();

        // Find the "events" array
        int eventsStart = json.indexOf("\"events\"");
        if (eventsStart == -1)
            return events;

        int arrayStart = json.indexOf('[', eventsStart);
        if (arrayStart == -1)
            return events;

        // Find matching closing bracket
        int arrayEnd = findMatchingBracket(json, arrayStart);
        if (arrayEnd == -1)
            return events;

        String arrayStr = json.substring(arrayStart + 1, arrayEnd).trim();
        if (arrayStr.isEmpty())
            return events;

        // Parse individual event objects
        int pos = 0;
        while (pos < arrayStr.length()) {
            int objStart = arrayStr.indexOf('{', pos);
            if (objStart == -1)
                break;

            int objEnd = arrayStr.indexOf('}', objStart);
            if (objEnd == -1)
                break;

            String objStr = arrayStr.substring(objStart + 1, objEnd);
            Map<String, Object> event = parseSimpleJsonObject(objStr);
            if (!event.isEmpty()) {
                events.add(event);
            }

            pos = objEnd + 1;
        }

        return events;
    }

    /**
     * Find matching closing bracket for an opening bracket.
     */
    private static int findMatchingBracket(String json, int openPos) {
        int depth = 0;
        for (int i = openPos; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[')
                depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0)
                    return i;
            }
        }
        return -1;
    }

    /**
     * Parse a simple flat JSON object string (without nested objects).
     * Input: "roundId":"R1","participant":"Alice","score":10
     */
    private static Map<String, Object> parseSimpleJsonObject(String objStr) {
        Map<String, Object> map = new LinkedHashMap<>();
        String[] pairs = objStr.split(",");

        for (String pair : pairs) {
            String[] kv = pair.split(":", 2);
            if (kv.length != 2)
                continue;

            String key = kv[0].trim().replace("\"", "");
            String value = kv[1].trim();

            if (value.startsWith("\"") && value.endsWith("\"")) {
                // String value
                map.put(key, value.substring(1, value.length() - 1));
            } else {
                // Numeric value
                try {
                    map.put(key, Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    map.put(key, value);
                }
            }
        }

        return map;
    }

    /**
     * Extract the pollIndex from the JSON response.
     */
    private static int parsePollIndex(String json) {
        int idx = json.indexOf("\"pollIndex\"");
        if (idx == -1)
            return -1;
        int colonIdx = json.indexOf(':', idx);
        if (colonIdx == -1)
            return -1;

        StringBuilder num = new StringBuilder();
        for (int i = colonIdx + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c) || c == '-') {
                num.append(c);
            } else if (num.length() > 0) {
                break;
            }
        }
        return num.length() > 0 ? Integer.parseInt(num.toString()) : -1;
    }

    /**
     * Build a JSON string for the leaderboard submission.
     */
    private static String buildSubmitJson(String regNo, List<Map.Entry<String, Integer>> leaderboard) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"regNo\":\"").append(regNo).append("\",\"leaderboard\":[");

        for (int i = 0; i < leaderboard.size(); i++) {
            Map.Entry<String, Integer> entry = leaderboard.get(i);
            if (i > 0)
                sb.append(",");
            sb.append("{\"participant\":\"").append(entry.getKey())
                    .append("\",\"totalScore\":").append(entry.getValue()).append("}");
        }

        sb.append("]}");
        return sb.toString();
    }

    // Main Logic

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("  Quiz Leaderboard System (Java)");
        System.out.println("  Registration No: " + REG_NO);
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println();

        // Step 1 & 2: Poll the API 10 times and collect all events
        List<Map<String, Object>> allEvents = new ArrayList<>();

        for (int poll = 0; poll < POLL_COUNT; poll++) {
            String url = BASE_URL + "/quiz/messages?regNo=" + URLEncoder.encode(REG_NO, "UTF-8") + "&poll=" + poll;
            System.out.println("[Poll " + poll + "] Fetching...");

            try {
                String response = httpGet(url);
                int pollIndex = parsePollIndex(response);
                List<Map<String, Object>> events = parseEvents(response);
                System.out.println(
                        "[Poll " + poll + "] Received " + events.size() + " events (pollIndex: " + pollIndex + ")");
                allEvents.addAll(events);
            } catch (Exception e) {
                System.err.println("[Poll " + poll + "] Error: " + e.getMessage());
            }

            // Wait 5 seconds before the next poll (skip delay after the last poll)
            if (poll < POLL_COUNT - 1) {
                System.out.println("  ⏳ Waiting " + (POLL_DELAY_MS / 1000) + "s before next poll...");
                System.out.println();
                Thread.sleep(POLL_DELAY_MS);
            }
        }

        System.out.println();
        System.out.println("── Total raw events collected: " + allEvents.size() + " ──");
        System.out.println();

        // Step 3: Deduplicate using (roundId + participant)
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> uniqueEvents = new ArrayList<>();

        for (Map<String, Object> event : allEvents) {
            String key = event.get("roundId") + "|" + event.get("participant");
            if (seen.add(key)) { // add() returns true if the element was new
                uniqueEvents.add(event);
            }
        }

        System.out.println("── Unique events after deduplication: " + uniqueEvents.size() + " ──");
        System.out.println();

        // Step 4: Aggregate scores per participant
        Map<String, Integer> scoreMap = new LinkedHashMap<>();

        for (Map<String, Object> event : uniqueEvents) {
            String participant = (String) event.get("participant");
            int score = (Integer) event.get("score");
            scoreMap.merge(participant, score, Integer::sum);
        }

        // Step 5: Generate leaderboard sorted by totalScore (descending)
        List<Map.Entry<String, Integer>> leaderboard = scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .collect(Collectors.toList());

        // Step 6: Compute total score across all users
        int totalScore = leaderboard.stream().mapToInt(Map.Entry::getValue).sum();

        System.out.println("── Leaderboard ──");
        System.out.println("┌──────┬────────────────────┬────────────┐");
        System.out.println("│ Rank │ Participant        │ Score      │");
        System.out.println("├──────┼────────────────────┼────────────┤");
        for (int i = 0; i < leaderboard.size(); i++) {
            Map.Entry<String, Integer> entry = leaderboard.get(i);
            System.out.printf("│ %4d │ %-18s │ %10d │%n", i + 1, entry.getKey(), entry.getValue());
        }
        System.out.println("└──────┴────────────────────┴────────────┘");
        System.out.println();
        System.out.println("  Total Score (all users): " + totalScore);
        System.out.println();

        // Step 7: Submit leaderboard
        String submitUrl = BASE_URL + "/quiz/submit";
        String submitBody = buildSubmitJson(REG_NO, leaderboard);

        System.out.println("── Submitting leaderboard... ──");
        System.out.println();
        System.out.println("Request body:");
        System.out.println(submitBody);
        System.out.println();

        try {
            String result = httpPost(submitUrl, submitBody);
            System.out.println("── Submission Response ──");
            System.out.println(result);
            System.out.println();

            if (result.contains("\"isCorrect\":true")) {
                System.out.println("✅ SUCCESS — Leaderboard is correct!");
            } else if (result.contains("\"isCorrect\":false")) {
                System.out.println("❌ INCORRECT — Please review deduplication logic.");
            } else {
                System.out.println("📋 Submission recorded successfully.");
            }
        } catch (Exception e) {
            System.err.println("Submission failed: " + e.getMessage());
        }
    }
}
