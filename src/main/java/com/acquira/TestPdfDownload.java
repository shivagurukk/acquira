package com.acquira;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestPdfDownload {
    public static void main(String[] args) {
        try {
            // 1. Login
            String loginUrlStr = "http://localhost:8081/api/auth/login";
            String jsonInputString = "{\"username\": \"admin\", \"password\": \"password\"}";

            System.out.println("Logging in to: " + loginUrlStr);
            URL loginUrl = new URI(loginUrlStr).toURL();
            HttpURLConnection loginConn = (HttpURLConnection) loginUrl.openConnection();
            loginConn.setRequestMethod("POST");
            loginConn.setRequestProperty("Content-Type", "application/json; utf-8");
            loginConn.setRequestProperty("Accept", "application/json");
            loginConn.setDoOutput(true);

            try (OutputStream os = loginConn.getOutputStream()) {
                byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int loginCode = loginConn.getResponseCode();
            System.out.println("Login Response Code: " + loginCode);

            if (loginCode != 200) {
                printErrorStream(loginConn);
                System.exit(1);
            }

            StringBuilder loginResponse = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(loginConn.getInputStream(), "utf-8"))) {
                String responseLine = null;
                while ((responseLine = br.readLine()) != null) {
                    loginResponse.append(responseLine.trim());
                }
            }

            String responseBody = loginResponse.toString();
            Pattern pattern = Pattern.compile("\"jwt\":\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(responseBody);
            String jwt = null;
            if (matcher.find()) {
                jwt = matcher.group(1);
            } else {
                System.err.println("JWT not found in response: " + responseBody);
                System.exit(1);
            }
            System.out.println("JWT Token obtained: " + jwt.substring(0, Math.min(20, jwt.length())) + "...");

            // 2. Test Session Endpoint
            String sessionUrlStr = "http://localhost:8081/api/auth/session";
            System.out.println("Testing Session Endpoint: " + sessionUrlStr);
            URL sessionUrl = new URI(sessionUrlStr).toURL();
            HttpURLConnection sessionConn = (HttpURLConnection) sessionUrl.openConnection();
            sessionConn.setRequestMethod("GET");
            sessionConn.setRequestProperty("Authorization", "Bearer " + jwt);

            int sessionCode = sessionConn.getResponseCode();
            System.out.println("Session Response Code: " + sessionCode);
            if (sessionCode != 200) {
                printErrorStream(sessionConn);
            } else {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(sessionConn.getInputStream(), "utf-8"))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null)
                        sb.append(line);
                    System.out.println("Session Output: " + sb.toString());
                }
            }

            // 3. Download PDF
            String pdfUrlStr = "http://localhost:8081/api/business/insights/pdf?merchantId=1";
            System.out.println("Testing PDF Download from: " + pdfUrlStr);
            URL pdfUrl = new URI(pdfUrlStr).toURL();
            HttpURLConnection pdfConn = (HttpURLConnection) pdfUrl.openConnection();
            pdfConn.setRequestMethod("GET");
            pdfConn.setRequestProperty("Authorization", "Bearer " + jwt);
            pdfConn.setRequestProperty("X-Tenant-Id", "1");

            int pdfCode = pdfConn.getResponseCode();
            System.out.println("PDF Response Code: " + pdfCode);

            if (pdfCode == HttpURLConnection.HTTP_OK) {
                try (InputStream in = pdfConn.getInputStream()) {
                    Path target = Path.of("test_report.pdf");
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    long size = Files.size(target);
                    System.out.println("PDF downloaded successfully. Size: " + size + " bytes");
                }
            } else {
                System.err.println("Failed to download PDF.");
                printErrorStream(pdfConn);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printErrorStream(HttpURLConnection conn) {
        try {
            InputStream es = conn.getErrorStream();
            if (es != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(es, "utf-8"))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine = null;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    System.err.println("Error Stream: " + response.toString());
                }
            } else {
                System.err.println("Error Stream is null.");
            }
        } catch (Exception e) {
            System.err.println("Could not read error stream: " + e.getMessage());
        }
    }
}
